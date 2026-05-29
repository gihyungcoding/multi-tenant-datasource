# ADR 002: 테넌트 컨텍스트 전파 — ThreadLocal과 Virtual Thread 고려사항

## 상태

채택 (Accepted)

## 컨텍스트

HTTP 요청마다 테넌트 식별자를 애플리케이션 전반에 전파해야 한다.
`TenantInterceptor`에서 추출한 테넌트 ID가 `RoutingDataSource`까지
안전하게 전달되어야 한다.

Spring Boot 4.0에서는 Virtual Thread가 기본 활성화되어 있어
전통적인 ThreadLocal 사용에 대한 검토가 필요했다.

## 결정

`ThreadLocal`을 사용하되, Virtual Thread 환경에서의 주의사항을 명시하고
`TenantContextHolder` 인터페이스로 추상화한다.

## 후보 비교

### 후보 1. ThreadLocal (채택)

```java
public class TenantContextHolderImpl implements TenantContextHolder {
    private static final ThreadLocal<TenantId> CURRENT_TENANT = new ThreadLocal<>();
}
```

**장점:**
- 스레드 간 컨텍스트 격리 보장.
- Spring MVC의 요청 처리 모델(스레드당 요청)과 자연스럽게 맞음.
- Virtual Thread에서도 각 Virtual Thread가 독립적인 ThreadLocal을 가지므로
  격리 보장됨.

**주의사항:**
- `afterCompletion()`에서 반드시 `clear()` 호출 필요 (메모리 릭 방지).
- `@Async`, `CompletableFuture` 등 비동기 처리 시 컨텍스트가 자동 전파되지 않음.
- `InheritableThreadLocal` 사용 시 Virtual Thread의 carrier thread 공유로
  예상치 못한 동작 발생 가능 → 사용 지양 (아래 상세 설명 참고).

### 후보 2. Request Scope Bean

```java
@Component
@RequestScope
public class TenantContextHolder {
    private TenantId tenantId;
}
```

**문제점:**
- Spring Web 컨텍스트에서만 사용 가능.
- 비동기 처리, 스케줄러 등 웹 요청 외부에서 사용 불가.
- 도메인/인프라 계층에서 Spring Web 의존성이 생김.

### 후보 3. MDC (Mapped Diagnostic Context)

```java
MDC.put("tenantId", tenantId.value());
```

**문제점:**
- 로깅 목적으로 설계되어 DataSource 라우팅 용도로 부적합.
- 내부적으로 ThreadLocal 사용이므로 동일한 주의사항 적용.

### 후보 4. 메서드 파라미터 전달

모든 메서드 시그니처에 `TenantId` 파라미터를 추가.

**문제점:**
- 도메인/인프라 경계를 넘나들며 모든 레이어가 오염됨.
- `RoutingDataSource.determineCurrentLookupKey()`는 파라미터를 받지 않는 구조.

## Virtual Thread 환경에서의 ThreadLocal

Spring Boot 4.0 기본 활성화된 Virtual Thread 환경에서의 동작:

```
Platform Thread (carrier)
└── Virtual Thread 1 → ThreadLocal A (독립적)
└── Virtual Thread 2 → ThreadLocal B (독립적)
└── Virtual Thread 3 → ThreadLocal C (독립적)
```

Virtual Thread는 각자 독립적인 ThreadLocal 저장소를 가지므로
기존 ThreadLocal 코드는 그대로 동작한다. (100개 Virtual Thread 동시 실행 테스트로 검증)

### InheritableThreadLocal 사용 금지 이유

```java
// 위험한 예시
private static final InheritableThreadLocal<TenantId> CURRENT_TENANT
    = new InheritableThreadLocal<>();

// Virtual Thread가 carrier thread를 재사용할 때
// 이전 요청의 테넌트 값이 잘못 상속될 수 있음
```

## 생명주기 관리

`TenantInterceptor`가 요청 시작과 종료 시점에 ThreadLocal을 명시적으로 관리한다.

```java
// 요청 시작
boolean preHandle(...) {
    resolveUseCase.resolve(new ResolveTenantCommand(tenantId));  // setTenant() 내부 호출
    return true;
}

// 요청 종료 — 예외 발생 시에도 반드시 실행
void afterCompletion(...) {
    contextHolder.clear();  // ThreadLocal.remove() → 스레드 풀 재사용 시 값 누수 방지
}
```

## 비동기 처리 시 명시적 전파 방법

`@Async` 또는 `CompletableFuture` 사용 시 테넌트를 명시적으로 전파해야 한다.

```java
// 비동기 작업 전 테넌트 캡처
TenantId currentTenant = contextHolder.getTenant();

CompletableFuture.runAsync(() -> {
    try {
        contextHolder.setTenant(currentTenant); // 명시적 전파
        // 비즈니스 로직
    } finally {
        contextHolder.clear();
    }
});
```

## 인터페이스 추상화 이유

`TenantContextHolder`를 도메인 패키지(`domain.context`)에 인터페이스로 정의하고
구현체(`TenantContextHolderImpl`)를 인프라 계층에 둔 이유:

- 도메인 계층이 ThreadLocal이라는 구체 기술에 의존하지 않음.
- 서비스 레이어 단위 테스트 시 Mockito Mock으로 쉽게 대체 가능.
- 향후 다른 전파 방식(예: Java 21 `ScopedValue`, Reactor Context)으로 교체 시 구현체만 변경.

> **참고**: Java 21에서 도입된 `ScopedValue`는 불변 + 스레드 격리가 보장되어 Virtual Thread에 더 적합한 API이다. 단, Spring의 `AbstractRoutingDataSource`가 ThreadLocal 기반으로 동작하는 것과의 일관성을 유지하기 위해 현재는 ThreadLocal을 사용한다.

## 결과

- 요청 스레드 간 테넌트 컨텍스트 완전 격리.
- Virtual Thread 환경에서 안전하게 동작.
- 인터페이스 추상화로 기술 변경에 유연하게 대응 가능.

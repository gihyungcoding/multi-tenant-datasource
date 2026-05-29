# ADR 001: 테넌트 DataSource 초기화 타이밍 — SmartInitializingSingleton 선택

## 상태

채택 (Accepted)

## 컨텍스트

멀티테넌시 환경에서 애플리케이션 시작 시 마스터 DB로부터 테넌트 목록을 읽어
각 테넌트의 DataSource를 미리 준비해야 한다.

이 초기화가 **Tomcat이 포트를 열기 전에 완료**되어야 한다.
초기화 전에 요청이 들어오면 테넌트 라우팅 테이블이 비어있어 잘못된 DataSource로
연결되거나 예외가 발생한다.

초기화 시점을 제어하는 후보는 세 가지였다.

## 결정

`SmartInitializingSingleton`을 구현하여 테넌트 DataSource를 초기화한다.

## 후보 비교

### 후보 1. `@PostConstruct`

```java
@Component
public class TenantDataSourceInitializer {
    @PostConstruct
    public void init() {
        // 해당 빈이 생성될 때 실행
    }
}
```

**문제점:**
- 해당 빈의 초기화 시점에 실행되므로 의존하는 다른 빈(Repository 등)이
  아직 완전히 준비되지 않았을 수 있다.
- 특히 JPA Repository가 프록시 초기화 전일 경우 예외 발생 가능.

### 후보 2. `ApplicationListener<ContextRefreshedEvent>`

```java
@Component
public class TenantDataSourceInitializer
        implements ApplicationListener<ContextRefreshedEvent> {
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
        // Context refresh마다 실행
    }
}
```

**문제점:**
- Context가 refresh될 때마다 실행되므로 중복 초기화 발생 가능.
- 자식 Context가 있는 경우(예: Spring MVC) 두 번 실행될 수 있다.
- 중복 방지 플래그를 별도로 관리해야 하는 부담이 생긴다.

### 후보 3. `SmartInitializingSingleton` (채택)

```java
@Component
public class TenantDataSourceInitializer
        implements SmartInitializingSingleton {
    @Override
    public void afterSingletonsInstantiated() {
        // 모든 싱글톤 빈 초기화 완료 후 단 한 번 실행
        persistencePort.findAllActive().forEach(tenant ->
            dataSourcePort.register(tenant.getId(), tenant.getDataSourceSpec())
        );
    }
}
```

**장점:**
- 모든 싱글톤 빈(JPA Repository, HikariCP 등)이 완전히 초기화된 이후 실행.
- Tomcat 포트가 열리기 전 단계이므로 초기화 완료 전 요청 유입 불가.
- 정확히 한 번만 실행됨이 보장된다.

## 실행 순서 (Spring Boot 기준)

```
1. 빈 정의 로딩
2. 싱글톤 빈 초기화 (@PostConstruct 포함)
3. SmartInitializingSingleton.afterSingletonsInstantiated()  ← 여기서 테넌트 초기화
4. Tomcat 포트 오픈
5. 요청 수신 시작
```

## 결과

- 테넌트 라우팅 테이블이 첫 요청 전에 반드시 준비됨.
- 초기화 중복 실행 없음.
- 의존 빈의 준비 상태를 보장받음.

## 참고

- [SmartInitializingSingleton JavaDoc](https://docs.spring.io/spring-framework/docs/current/javadoc-api/org/springframework/beans/factory/SmartInitializingSingleton.html)

# ADR 004: 헥사고날 아키텍처(Ports & Adapters) 채택

## 상태

채택 (Accepted)

## 컨텍스트

멀티 테넌트 DataSource 라우팅 기능을 구현할 때 다음과 같은 설계 목표가 있었다.

- **핵심 비즈니스 규칙(도메인)이 인프라 기술에 의존하지 않아야 한다.**  
  JPA, PostgreSQL, Spring 빈 선언 방식이 바뀌어도 도메인 로직은 수정 없이 유지되어야 한다.
- **테스트 용이성**: 도메인 로직과 애플리케이션 서비스는 실제 DB 없이 단위 테스트할 수 있어야 한다.
- **교체 가능성**: 추후 PostgreSQL을 다른 DB로 교체하거나, REST API를 다른 프로토콜로 교체할 때 변경 범위를 최소화해야 한다.

전통적인 레이어드 아키텍처(Controller → Service → Repository)는 Service가 Repository 구현체를 직접 참조하므로, 인프라 변경 시 Service 코드까지 수정해야 하는 결합도 문제가 있다.

## 결정

**헥사고날 아키텍처(Ports & Adapters)**를 적용한다.

```
interfaces/      ← Controller, Interceptor (Driving Adapter)
application/     ← UseCase Interface (Inbound Port), Service, Port Interface (Outbound Port)
domain/          ← 순수 도메인 (외부 의존 없음)
infrastructure/  ← JPA, HikariCP, RoutingDataSource (Driven Adapter)
```

**의존성 방향**

```
interfaces ──→ application ──→ domain ←── infrastructure
```

모든 의존성은 바깥에서 안쪽(도메인)으로만 향한다.

### 핵심 규칙

1. **인바운드 포트**: `UseCase` 인터페이스(`RegisterTenantUseCase`, `GetTenantUseCase` 등)로 정의한다.  
   Controller는 UseCase 인터페이스만 알고, 구현체(Service)를 직접 알지 못한다.

2. **아웃바운드 포트**: `Port` 인터페이스(`TenantPersistencePort`, `TenantDataSourcePort`, `TenantContextHolder`)로 정의한다.  
   Service는 Port 인터페이스만 알고, JPA Repository 등 구현체를 직접 알지 못한다.

3. **도메인 순수성**: 도메인 객체(`Tenant`, `TenantId`, `DataSourceSpec`)는 Spring, JPA 등 어떤 프레임워크 어노테이션도 포함하지 않는다.

4. **JPA Entity 은닉**: JPA Entity(`TenantJpaEntity`, `DemoMessageJpaEntity`)는 인프라 패키지 내부에서만 사용하며 `package-private`으로 선언한다.

## 결과

### 장점

- 도메인 로직은 Mockito만으로 단위 테스트 가능 (Spring 컨텍스트 불필요).
- JPA Repository를 다른 영속 기술로 교체해도 Service/Domain 코드는 수정 불필요.
- 각 레이어가 명확히 분리되어 코드 탐색 및 온보딩이 용이하다.

### 단점 / 트레이드오프

- 보일러플레이트 증가: 동일 데이터를 표현하는 Domain Object, JPA Entity, DTO, Result 객체가 각각 존재한다.
- 소규모 CRUD 기능에도 UseCase 인터페이스 + Port 인터페이스를 모두 작성해야 하므로 초기 작성량이 많다.
- 팀이 헥사고날 아키텍처에 익숙하지 않은 경우 학습 비용이 발생한다.

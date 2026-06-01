# ADR 008: Dual EntityManagerFactory — 마스터 DB와 테넌트 DB의 JPA 분리

## 상태

채택 (Accepted)

## 컨텍스트

Spring Boot가 자동 구성하는 단일 `EntityManagerFactory`는 `@Primary DataSource`(마스터 DB)에 바인딩된다.
이 구조에서 `DemoMessageJpaEntity`와 `TenantJpaEntity` 모두 마스터 DB로 라우팅되어
테넌트 격리가 깨지는 문제가 발생했다.

```
[단일 EMF — 문제 상황]

TenantJpaEntity    ─┐
                    ├──▶ masterEntityManagerFactory ──▶ 마스터 DB  ✓
DemoMessageJpaEntity─┘
                                                                    ✗ 테넌트 데이터가 마스터 DB에 저장됨
```

핵심 요구사항:
- `TenantJpaEntity`: 테넌트 메타데이터(연결 정보·상태) → **마스터 DB** (항상 단일)
- `DemoMessageJpaEntity`: 테넌트 비즈니스 데이터 → **테넌트 DB** (RoutingDataSource 경유)

## 결정

Spring Boot JPA 자동 구성을 비활성화하고, 두 EMF를 명시적으로 선언한다.

```
[Dual EMF — 결정 후]

TenantJpaEntity     ──▶ masterEntityManagerFactory ──▶ 마스터 DB         ✓
DemoMessageJpaEntity──▶ tenantEntityManagerFactory ──▶ RoutingDataSource ──▶ 테넌트 DB ✓
```

### 1단계 — 자동 구성 비활성화

```java
@SpringBootApplication(exclude = {
    HibernateJpaAutoConfiguration.class,
    DataJpaRepositoriesAutoConfiguration.class
})
```

Boot의 단일 EMF 자동 구성을 제거하고, 이후 모든 JPA 설정을 코드로 직접 제어한다.

---

### 2단계 — MasterJpaConfig

```java
@Configuration
@EnableJpaRepositories(
    basePackages           = "...infrastructure.persistence",
    entityManagerFactoryRef = "masterEntityManagerFactory",
    transactionManagerRef  = "masterTransactionManager"
)
public class MasterJpaConfig {

    @Bean @Primary
    public LocalContainerEntityManagerFactoryBean masterEntityManagerFactory(
            DataSource masterDataSource) {
        // ① schema.sql 실행 (IF NOT EXISTS — 멱등)
        DatabasePopulatorUtils.execute(MASTER_SCHEMA_POPULATOR, masterDataSource);

        // ② EMF: validate — 엔티티↔스키마 정합성 검증
        // ...
        props.setProperty("hibernate.hbm2ddl.auto", "validate");
    }

    @Bean @Primary
    public PlatformTransactionManager masterTransactionManager(...) { ... }
}
```

**`@Primary` 선언 이유**: `@Transactional` 한정자 없이 사용하는 서비스가 마스터 TM을
자동으로 선택하도록 한다. 인프라 전반의 기본 트랜잭션 경계를 마스터로 고정한다.

---

### 3단계 — TenantJpaConfig

```java
@Configuration
@EnableJpaRepositories(
    basePackages           = "...demo.infrastructure",
    entityManagerFactoryRef = "tenantEntityManagerFactory",
    transactionManagerRef  = "tenantTransactionManager"
)
public class TenantJpaConfig {

    @Bean
    public LocalContainerEntityManagerFactoryBean tenantEntityManagerFactory(
            RoutingDataSource routingDataSource) {
        LazyConnectionDataSourceProxy lazyDs =
                new LazyConnectionDataSourceProxy(routingDataSource);
        // ...
        props.setProperty("hibernate.dialect",      "org.hibernate.dialect.PostgreSQLDialect");
        props.setProperty("hibernate.hbm2ddl.auto", "none");
    }

    @Bean
    public PlatformTransactionManager tenantTransactionManager(...) { ... }
}
```

---

### 4단계 — 테넌트 스키마 초기화

`hbm2ddl.auto=none`이므로 테넌트 등록 시점에 직접 DDL을 실행한다.

```java
// TenantDataSourceAdapter.register() 내부
Map<String, DataSource> snapshot = registry.registerAndSnapshot(tenantId, spec);
schemaInitializer.initialize(registry.get(tenantId));  // ← 신규 테넌트 스키마 생성
routingDataSource.refresh(snapshot);
```

```java
// TenantSchemaInitializer
private static final ResourceDatabasePopulator POPULATOR =
        new ResourceDatabasePopulator(new ClassPathResource("db/tenant/schema.sql"));

public void initialize(DataSource dataSource) {
    DatabasePopulatorUtils.execute(POPULATOR, dataSource);
}
```

---

### 5단계 — 트랜잭션 경계 명시

`DemoService`는 테넌트 TM을 명시적으로 지정한다.

```java
@Transactional("tenantTransactionManager")
public DemoMessageResult save(SaveDemoMessageCommand command) { ... }

@Transactional(value = "tenantTransactionManager", readOnly = true)
public List<DemoMessageResult> findAll() { ... }
```

한정자 없는 `@Transactional`이 `@Primary` 마스터 TM으로 향하므로,
테넌트 DB를 다루는 서비스에서는 반드시 명시가 필요하다.

---

### 주요 기술 선택 3가지

#### A. LazyConnectionDataSourceProxy

기동 시 `RoutingDataSource`에 등록된 테넌트가 없다.
Hibernate가 `SessionFactory` 초기화 중 `getConnection()`을 호출하면
테넌트 컨텍스트가 없어 `TenantContextMissingException`이 발생한다.

`LazyConnectionDataSourceProxy`로 감싸면 실제 JDBC 문(Statement) 실행 직전까지
커넥션 획득이 지연되므로, 기동 시 예외 없이 EMF를 생성할 수 있다.

| 방식 | 기동 시 안전성 | 런타임 동작 |
|------|--------------|------------|
| RoutingDataSource 직접 사용 | ✗ (TenantContextMissingException) | - |
| LazyConnectionDataSourceProxy 래핑 | ✓ (커넥션 지연 획득) | 정상 |

`hibernate.dialect` 명시를 병행하여 Hibernate가 다이얼렉트 감지를 위한
JDBC 메타데이터 조회를 생략하도록 한다.

#### B. schema.sql 기반 스키마 관리

| 항목 | 마스터 DB | 테넌트 DB |
|------|-----------|-----------|
| DDL 위치 | `db/master/schema.sql` | `db/tenant/schema.sql` |
| 실행 시점 | 기동 시 1회 (`masterEntityManagerFactory` 빈 생성 전) | 테넌트 등록 시마다 |
| DDL 멱등성 | `IF NOT EXISTS` | `IF NOT EXISTS` |
| Hibernate 역할 | `validate` (정합성 검증) | `none` (DDL 미관여) |

`ddl-auto=update` 대신 `validate`를 선택한 이유:
- `update`는 컬럼 삭제·이름 변경 등 복잡한 스키마 변경을 처리하지 않는다.
- 엔티티와 스키마가 불일치하면 기동 시 즉시 실패하여 스키마 드리프트를 조기에 감지한다.
- SQL 파일이 단일 진실 공급원(Single Source of Truth)이 된다.

#### C. Naming Strategy 명시

자동 구성을 비활성화하면 Spring Boot가 적용하던 naming strategy도 함께 제거된다.
두 EMF 모두 동일한 전략을 명시하여 `camelCase` 필드 → `snake_case` 컬럼 변환을 보장한다.

```java
props.setProperty("hibernate.physical_naming_strategy",
        "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy");
props.setProperty("hibernate.implicit_naming_strategy",
        "org.springframework.boot.hibernate.SpringImplicitNamingStrategy");
```

명시하지 않으면 Hibernate 기본값(`PhysicalNamingStrategyStandardImpl`)이 적용되어
`createdAt` 필드가 `createdat` 컬럼으로 매핑된다.

---

### 발견된 설계 누락 및 수정

Dual EMF 구현 과정에서 기존 설계의 누락이 드러났다.

#### `Tenant.createdAt` 미영속

도메인 객체에 `createdAt` 필드가 있었으나 `TenantJpaEntity`에 컬럼이 없었다.
`toDomain()`이 `Tenant.create()`를 호출하므로 DB에서 로드할 때마다 `createdAt`이
현재 시각으로 덮어씌워지는 버그가 존재했다.

```java
// 수정 전 — 로드할 때마다 createdAt = now()
private Tenant toDomain(TenantJpaEntity entity) {
    Tenant tenant = Tenant.create(id, spec);  // ← createdAt = LocalDateTime.now()
    if (status instanceof TenantStatus.Suspended s) {
        tenant.suspend(s.reason());
    }
    return tenant;
}

// 수정 후 — DB 값 그대로 복원
private Tenant toDomain(TenantJpaEntity entity) {
    return Tenant.restore(id, spec, status, entity.getCreatedAt());
}
```

`create()`와 `restore()`의 의미를 명확히 분리하여 영속 계층의 복원과 도메인 생성을 구분한다.

| 팩토리 메서드 | 호출 시점 | createdAt |
|-------------|---------|-----------|
| `Tenant.create(id, spec)` | 신규 등록 | `LocalDateTime.now()` 자동 세팅 |
| `Tenant.restore(id, spec, status, createdAt)` | DB 로드 | 인자로 전달받은 값 그대로 |

## 후보 비교

### 후보 1: Hibernate 멀티테넌시 API (채택하지 않음)

Hibernate 자체의 `MultiTenantConnectionProvider` + `CurrentTenantIdentifierResolver`를
활용하면 단일 EMF로 다수 테넌트 DB에 접속할 수 있다.

- **장점**: EMF가 하나이므로 트랜잭션 경계가 단순하다.
- **단점**: Hibernate 내부 멀티테넌시 API에 종속된다. Spring Data JPA와의 통합이
  비표준적이고, 캐시·통계 같은 EMF 수준 기능이 테넌트별로 분리되지 않는다.
  또한 마스터 DB(테넌트 메타데이터)와 테넌트 DB(비즈니스 데이터)가 동일한 EMF를
  사용해야 하므로, 두 DB의 역할 차이를 코드로 표현하기 어렵다.
- **채택하지 않은 이유**: 역할이 다른 두 DB를 명시적으로 분리하는 것이 더 직관적이며,
  Spring Data JPA의 표준 방식(복수 `@EnableJpaRepositories`)으로 구현 가능하다.

### 후보 2: ddl-auto=update 유지 (채택하지 않음)

마스터 DB는 `ddl-auto=update`로, 테넌트 DB는 테넌트 등록 시 자동 갱신한다.

- **장점**: 별도 schema.sql 파일 관리가 불필요하다.
- **단점**: 컬럼 삭제·이름 변경 등 복잡한 스키마 변경을 처리하지 않는다.
  스키마와 엔티티 불일치 시 런타임까지 발견되지 않을 수 있다.
  schema.sql이 없어 DDL의 단일 진실 공급원이 존재하지 않는다.
- **채택하지 않은 이유**: `validate`와 SQL 파일의 조합이 프로덕션 지향적이며,
  마스터 DB와 테넌트 DB 모두 일관된 방식으로 스키마를 관리할 수 있다.

### 후보 3: 단일 EMF + @Table(schema=...) 분리

단일 EMF를 유지하되 PostgreSQL 스키마(schema)를 테넌트별로 분리하는 방식.

- **장점**: EMF 분리 없이 테넌트 격리가 가능하다.
- **단점**: 단일 물리 DB 안에서의 격리이므로, 테넌트별 독립 DB를 목표로 하는
  ADR 000의 결정과 충돌한다. 또한 `RoutingDataSource`의 존재 의의를 잃는다.
- **채택하지 않은 이유**: 프로젝트 핵심 요구사항(테넌트별 독립 DB)에 위배된다.

## 결과

### 장점

- **진정한 데이터 격리**: `DemoMessageJpaEntity`가 테넌트 DB에 저장되어
  ADR 000의 요구사항이 충족된다.
- **명확한 트랜잭션 경계**: 마스터 TM(기본)과 테넌트 TM(명시)이 분리되어
  어느 DB에서 트랜잭션이 열리는지 코드에서 명확하게 드러난다.
- **스키마 버전 관리 가능**: `schema.sql` 파일이 단일 진실 공급원이 된다.
  Flyway/Liquibase로의 전환 경로가 열린다.
- **기동 시 정합성 검증**: `validate` 모드가 엔티티↔스키마 불일치를 기동 직후 감지한다.

### 단점 / 트레이드오프

- **테넌트 서비스에 TM 명시 필요**: `@Transactional("tenantTransactionManager")`를
  빠뜨리면 마스터 TM이 선택되어 런타임 에러가 발생한다. 신규 서비스 작성 시 주의가 필요하다.
- **테넌트 스키마 수동 관리**: `hbm2ddl.auto=none`이므로 엔티티 변경 시
  `db/tenant/schema.sql`도 함께 수정해야 한다. 현재 구조에서는 추가만 지원하며,
  컬럼 변경·삭제는 직접 마이그레이션 SQL을 작성해야 한다.
- **설정 코드 증가**: 자동 구성 대비 `MasterJpaConfig`·`TenantJpaConfig`·
  `TenantSchemaInitializer` 등 명시적 설정이 늘어난다.

## 테스트 전략

| 테스트 | 클래스 | 검증 내용 |
|--------|--------|-----------|
| **컨텍스트 로딩** | `MultiTenantDatasourceApplicationTests` | Dual EMF + validate 정합성 검증 통과 |
| **테넌트 격리** | `DemoMessageIsolationTest` | tenant-a 저장 데이터가 tenant-b에서 조회되지 않음 |
| **라우팅 통합** | `TenantRoutingIntegrationTest` | 기존 DataSource 라우팅 동작 유지 |
| **스키마 멱등성** | `DemoMessageIsolationTest.setUp()` 반복 실행 | 재기동 시 `IF NOT EXISTS`로 정상 통과 |

`DemoMessageIsolationTest`의 격리 검증은 두 개의 독립된 PostgreSQL 컨테이너
(`tenantADb`, `tenantBDb`)를 사용한다. 두 테넌트가 서로 다른 물리 DB를 가리키므로
데이터 격리가 실제 DB 수준에서 검증된다.

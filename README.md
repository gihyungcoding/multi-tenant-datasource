# multi-tenant-datasource

멀티테넌시 환경에서 테넌트별 DataSource를 동적으로 라우팅하는 Spring Boot 예제입니다.

---

## 왜 만들었나

### 배경 — 기존 구조의 문제

여러 고객사에 동일한 서비스를 제공하기 위해 **모놀리스 애플리케이션 1개를 고객사 수만큼
복제하여 서버에 배포**하고, 각 서버마다 `application.yml`의 DB 접속 정보만 다르게
설정하는 방식을 사용하고 있었습니다.

```
[기존 구조]

고객사 A 서버  ── application.yml (db: tenant_a) ── tenant_a DB
고객사 B 서버  ── application.yml (db: tenant_b) ── tenant_b DB
고객사 C 서버  ── application.yml (db: tenant_c) ── tenant_c DB
       ↑
  동일한 JAR을 서버마다 따로 배포
```

이 구조는 고객사가 늘어날수록 아래 문제가 누적되었습니다.

- **배포 비용 증가**: 고객사 추가 시 서버 프로비저닝 → 설정 파일 작성 → 배포 파이프라인 구성을 매번 반복.
- **운영 부담**: 애플리케이션 업데이트 시 고객사 수만큼 배포 작업 필요. 누락 위험 존재.
- **자원 낭비**: 트래픽이 적은 고객사도 전용 서버를 점유. 유휴 자원 비율 높음.
- **설정 오염 위험**: 수동으로 관리하는 설정 파일이 늘어날수록 잘못된 DB를 바라보는 사고 발생 가능성 증가.

### 개선 목표 — 단일 애플리케이션으로 통합

모놀리스를 고객사마다 복제 배포하는 구조를 **단일 애플리케이션이 모든 테넌트를 처리**하는
구조로 전환합니다.

```
[개선된 구조]

                        ┌──→ tenant_a DB
클라이언트 ──→ 애플리케이션 1개 ──→ tenant_b DB
                        └──→ tenant_c DB

X-Tenant-Id 헤더로 테넌트 식별
RoutingDataSource로 동적 DataSource 라우팅
```

- 고객사 추가 시 서버 배포 없이 **DB 등록만으로 온보딩** 가능.
- 애플리케이션 업데이트를 **단일 배포**로 모든 고객사에 적용.
- 서버 자원을 테넌트 간 공유하여 **유휴 자원 감소**.

---

## 아키텍처

헥사고날 아키텍처(Ports & Adapters)를 적용했습니다.

```
interfaces/      ← Controller, Interceptor (Driving Adapter)
application/     ← UseCase Interface (Inbound Port), Service, Port (Outbound Port)
domain/          ← 순수 도메인 (외부 의존 없음)
infrastructure/  ← JPA, HikariCP, RoutingDataSource (Driven Adapter)
```

**의존성 방향**

```
interfaces ──→ application ──→ domain ←── infrastructure
```

도메인은 Spring, JPA 등 외부 기술에 전혀 의존하지 않습니다.

### 요청 처리 흐름

```
HTTP 요청 (X-Tenant-Id: tenant-a)
  → TenantInterceptor          X-Tenant-Id 헤더 추출
  → ResolveTenantDataSourceUseCase  테넌트 유효성 검증 (ACTIVE 여부)
  → TenantContextHolder        ThreadLocal에 TenantId 저장
  → RoutingDataSource.determineCurrentLookupKey()  → "tenant-a"
  → tenant-a 전용 DataSource로 라우팅
  → afterCompletion()          TenantContextHolder.clear()
```

---

## 기술 스택

| 항목 | 기술 | 선택 근거 |
|---|---|---|
| Language | Java 21 | Virtual Thread, Record, Sealed Class, LTS |
| Framework | Spring Boot 4.0.6 | 최신 안정 버전, Spring Framework 7 기반 |
| ORM | Spring Data JPA + Hibernate 7 | 마스터 DB 접근 |
| Connection Pool | HikariCP 7 | Spring Boot 기본 내장, 성능 |
| Database | PostgreSQL 16 | 테넌트별 독립 DB |
| Build | Gradle 9 (Kotlin DSL) | 타입 안전, IDE 지원 |
| Test | JUnit 5 + Testcontainers | 실제 DB 환경 통합 테스트 |

---

## 실행 방법

### 사전 요구사항

- Java 21
- Docker

### 1. DB 실행

```bash
docker compose up -d
```

| 컨테이너 | 호스트 포트 | DB명 | 계정 |
|---------|-----------|------|------|
| master-db | 5432 | master | master / master |
| tenant-a-db | 5433 | tenant_a | tenant_a / tenant_a |
| tenant-b-db | 5434 | tenant_b | tenant_b / tenant_b |

### 2. 앱 실행

```bash
./gradlew bootRun
```

### 3. 테넌트 등록

```bash
curl -X POST http://localhost:8080/api/tenants \
  -H "Content-Type: application/json" \
  -d '{
    "tenantId": "tenant-a",
    "url": "jdbc:postgresql://localhost:5433/tenant_a",
    "username": "tenant_a",
    "password": "tenant_a"
  }'
```

### 4. 테넌트 라우팅 확인

```bash
# tenant-a 로 메시지 저장
curl -X POST http://localhost:8080/api/demo \
  -H "X-Tenant-Id: tenant-a" \
  -H "Content-Type: application/json" \
  -d '{ "content": "hello from tenant-a" }'

# tenant-a 의 메시지 조회 (tenant-b 로 조회하면 데이터 없음 — 격리 확인)
curl http://localhost:8080/api/demo \
  -H "X-Tenant-Id: tenant-a"
```

---

## 핵심 구현

### SmartInitializingSingleton — 초기화 타이밍 보장

```java
@Component
public class TenantDataSourceInitializer implements SmartInitializingSingleton {

    @Override
    public void afterSingletonsInstantiated() {
        // 모든 싱글톤 빈 초기화 완료 후 실행
        // Tomcat 포트 오픈 전 단계 → 요청 유입 전 테넌트 맵 준비 완료 보장
        persistencePort.findAllActive().forEach(tenant ->
            dataSourcePort.register(tenant.getId(), tenant.getDataSourceSpec())
        );
    }
}
```

`@PostConstruct` 대신 `SmartInitializingSingleton`을 선택한 이유는
[ADR 001](docs/adr/001-why-smartinitializingsingleton.md)을 참고하세요.

### AbstractRoutingDataSource — 요청마다 DataSource 결정

```java
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        if (!contextHolder.hasTenant()) {
            throw new TenantContextMissingException();
        }
        return contextHolder.getTenant().value();  // e.g. "tenant-a"
    }
}
```

### TenantStatus — Sealed Interface로 상태 표현

```java
public sealed interface TenantStatus
    permits TenantStatus.Active, TenantStatus.Suspended {

    String ACTIVE_CODE    = "ACTIVE";
    String SUSPENDED_CODE = "SUSPENDED";

    String code();

    record Active() implements TenantStatus {
        public String code() { return ACTIVE_CODE; }
    }

    record Suspended(String reason) implements TenantStatus {
        public String code() { return SUSPENDED_CODE; }
    }
}
```

패턴 매칭으로 컴파일 타임에 모든 상태를 처리했는지 검증합니다.

---

## 패키지 구조

```
src/main/java/com/example/multitenant/
├── domain/
│   ├── tenant/
│   │   ├── Tenant.java                    # 애그리거트 루트
│   │   ├── TenantId.java                  # 값 객체
│   │   ├── TenantStatus.java              # Sealed interface
│   │   └── DataSourceSpec.java            # 값 객체
│   ├── context/
│   │   └── TenantContextHolder.java       # 컨텍스트 포트 (인터페이스)
│   └── exception/
│       ├── DomainException.java
│       └── ErrorCode.java
│
├── application/
│   ├── port/
│   │   ├── in/                            # UseCase 인터페이스 + Command/Result
│   │   └── out/                           # TenantPersistencePort, TenantDataSourcePort
│   └── service/                           # RegisterTenantService, GetTenantService,
│                                          # ResolveTenantDataSourceService
│
├── infrastructure/
│   ├── datasource/                        # RoutingDataSource, TenantContextHolderImpl,
│   │   │                                  # TenantDataSourceAdapter, TenantDataSourceInitializer
│   │   └── config/                        # DataSourceConfig
│   └── persistence/                       # TenantJpaEntity, TenantJpaRepository,
│                                          # TenantPersistenceAdapter
│
├── interfaces/
│   ├── interceptor/                       # TenantInterceptor (X-Tenant-Id 헤더 처리)
│   └── web/                               # TenantController, GlobalExceptionHandler, DTO
│
└── demo/                                  # 테넌트 격리 동작 확인용 모듈
    ├── domain/                            # DemoMessage
    ├── application/                       # DemoService, UseCase 인터페이스, Port
    ├── infrastructure/                    # DemoMessageAdapter, JPA Entity
    └── interfaces/                        # DemoController
```

---

## 테스트

Google의 *How Google Tests Software* 기준으로 Small / Medium / Large 세 크기로 분류합니다.

| 크기 | 어노테이션 | 특징 | 대상 |
|------|-----------|------|------|
| **Small** | `@SmallTest` | I/O 없음, Spring 없음, < 100ms | 도메인 로직, 서비스 (Mockito) |
| **Medium** | `@MediumTest` | localhost 허용, Spring Slice | 컨트롤러 레이어 (MockMvc) |
| **Large** | `@LargeTest` | Docker 허용, 전체 컨텍스트, Testcontainers | DataSource 라우팅 E2E |

```bash
./gradlew test                   # 전체 실행 (현재 76개, 0 failures)
./gradlew test -Dgroups=small    # Small만 (Docker 불필요, 빠른 피드백)
./gradlew test -Dgroups=medium   # Medium만
./gradlew test -Dgroups=large    # Large만 (Docker 필요)
```

---

## 한계 및 개선 가능한 부분

- **단일 노드 구성**: 현재 테넌트 DataSource 맵은 인메모리 관리. 다중 인스턴스 환경에서는 테넌트 등록 시 모든 인스턴스에 전파하는 메커니즘 필요.
- **커넥션 풀 고정 사이즈**: 테넌트 수 증가 시 DB 커넥션 한계 도달 가능. PgBouncer 도입 권장 ([ADR 003](docs/adr/003-hikari-pool-per-tenant.md) 참고).
- **비동기 컨텍스트 전파**: `@Async`, `CompletableFuture` 사용 시 테넌트 컨텍스트 명시적 전파 필요 ([ADR 002](docs/adr/002-threadlocal-virtual-thread.md) 참고).
- **테넌트 동적 비활성화**: 현재 실행 중인 요청 처리 후 풀 종료하는 Graceful shutdown 미구현.
- **Master/Slave 분리**: 현재 테넌트별 DataSource가 단일 DB를 바라보는 구조. `@Transactional(readOnly)` 속성에 따라 Master/Slave를 자동 라우팅하는 구조로 확장 가능.

  ```
  [확장 구조]

  쓰기 트랜잭션 (@Transactional)            → tenant_a Master DB
  읽기 트랜잭션 (@Transactional(readOnly))   → tenant_a Slave DB (부하 분산)
  ```

  `RoutingDataSource.determineCurrentLookupKey()`에서 테넌트 ID와 트랜잭션 읽기/쓰기 여부를 조합한 복합 키로 구현 가능.

  ```java
  @Override
  protected Object determineCurrentLookupKey() {
      String tenantId = contextHolder.getTenant().value();
      boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
      return isReadOnly ? tenantId + ":slave" : tenantId + ":master";
  }
  ```

  단, `@Transactional(readOnly = true)` 시작 시점에 DataSource가 결정되므로
  `LazyConnectionDataSourceProxy`를 함께 적용하면 실제 커넥션 획득을 쿼리 시점까지 지연시켜 이 문제를 해결할 수 있다.

---

## 설계 결정 기록 (ADR)

| 번호 | 결정 |
|---|---|
| [ADR 000](docs/adr/000-multitenant-architecture.md) | 멀티테넌시 아키텍처 채택 근거 (독립 DB vs 공유 스키마 비교) |
| [ADR 001](docs/adr/001-why-smartinitializingsingleton.md) | SmartInitializingSingleton 선택 근거 |
| [ADR 002](docs/adr/002-threadlocal-virtual-thread.md) | ThreadLocal과 Virtual Thread 고려사항 |
| [ADR 003](docs/adr/003-hikari-pool-per-tenant.md) | 테넌트별 HikariCP 커넥션 풀 전략 |
| [ADR 004](docs/adr/004-hexagonal-architecture.md) | 헥사고날 아키텍처 채택 |
| [ADR 005](docs/adr/005-google-test-size-classification.md) | Google Small/Medium/Large 테스트 분류 |
| [ADR 006](docs/adr/006-singleton-testcontainers.md) | Singleton Testcontainers 패턴 |

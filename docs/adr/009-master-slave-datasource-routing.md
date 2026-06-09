# ADR 009: Master/Slave DataSource 라우팅 — 복합 키 + TransactionSynchronizationManager

## 상태

채택 (Accepted)

## 컨텍스트

기존 구조에서 각 테넌트의 DataSource 는 단일 DB 를 바라봤다.

```
[기존 구조]

쓰기 요청  ─┐
            ├──▶ RoutingDataSource ──▶ "tenant-a" (HikariPool) ──▶ tenant_a DB
읽기 요청  ─┘
```

이 구조의 문제:
- 읽기 전용 쿼리(`SELECT`)와 쓰기 쿼리(`INSERT`·`UPDATE`·`DELETE`)가 동일한 DB 인스턴스를 공유한다.
- 트래픽이 증가하면 읽기 부하가 쓰기 성능에 직접 영향을 미친다.
- `@Transactional(readOnly = true)` 어노테이션이 DataSource 수준에서 아무 역할을 하지 않는다.

개선 목표:
- `@Transactional(readOnly = true)` 가 선언된 메서드는 slave DB 로 라우팅하여 읽기 부하를 분산한다.
- slave 미구성 테넌트는 기존과 동일하게 master 단독으로 동작한다 (하위 호환 보장).
- 라우팅 결정이 응용 코드에 노출되지 않아야 한다. 서비스 계층은 여전히 `@Transactional` 어노테이션만 선언한다.

## 결정

세 가지 변경을 조합하여 Master/Slave 라우팅을 구현한다.

### 1. 복합 키(Composite Key) 체계

`TenantDataSourceRegistry` 의 내부 맵 키를 변경한다.

```
[이전 키 체계]
dataSourceMap: { "tenant-a" → HikariDataSource }

[신규 키 체계]
dataSourceMap: {
    "tenant-a:master" → HikariDataSource (master)
    "tenant-a:slave"  → HikariDataSource (slave, 선택)
}
```

slave 는 옵션이다. 등록하지 않으면 `"tenant-a:slave"` 키가 맵에 존재하지 않는다.

---

### 2. RoutingDataSource — 키 추적 + readOnly 분기

```java
public class RoutingDataSource extends AbstractRoutingDataSource {

    private final TenantContextHolder contextHolder;

    /** refresh() 호출 시 갱신. slave 존재 여부 판별에 사용. */
    private volatile Set<String> registeredKeys = Set.of();

    @Override
    protected Object determineCurrentLookupKey() {
        if (!contextHolder.hasTenant()) {
            throw new TenantContextMissingException();
        }
        String tenantId = contextHolder.getTenant().value();
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        if (isReadOnly) {
            String slaveKey = tenantId + ":slave";
            if (registeredKeys.contains(slaveKey)) {
                return slaveKey;   // slave 로 라우팅
            }
        }
        return tenantId + ":master"; // master 로 라우팅 (기본)
    }

    public void refresh(Map<String, DataSource> dataSourceMap) {
        this.registeredKeys = Set.copyOf(dataSourceMap.keySet()); // 키 집합 갱신
        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
```

`registeredKeys` 를 `volatile Set` 으로 별도 관리하는 이유:
- `AbstractRoutingDataSource.resolvedDataSources` 는 `protected` 가 아닌 `private` 이므로 직접 접근 불가.
- `refresh()` 호출 시 원자적으로 갱신하므로 조회 중 NPE 없이 일관된 상태를 읽을 수 있다.

---

### 3. LazyConnectionDataSourceProxy 와의 연동 — 이미 해결됨

`@Transactional(readOnly = true)` 를 사용할 때 핵심 문제가 있다.

```
[타이밍 문제]

트랜잭션 시작(readOnly=true 플래그 설정)
    → DataSource.getConnection() 획득  ← 이 시점에 determineCurrentLookupKey() 호출
    → ... 쿼리 실행
```

Spring 의 `JpaTransactionManager` 는 트랜잭션 시작 시점에 곧바로 커넥션을 획득한다.
`@Transactional(readOnly = true)` 로 표시된 메서드에 진입할 때 `readOnly` 플래그가
`TransactionSynchronizationManager` 에 설정되기 **전**에 `determineCurrentLookupKey()` 가
호출될 수 있다 — 즉, slave 를 판별할 수 없는 상태에서 커넥션이 선택된다.

`LazyConnectionDataSourceProxy` 는 이 문제를 해결한다.

```
[LazyConnectionDataSourceProxy 적용 후]

트랜잭션 시작(readOnly=true 플래그 설정)
    → DataSource.getConnection() → LazyConnectionDataSourceProxy 가 프록시 커넥션 반환
          ↓ 실제 커넥션은 아직 미획득
    첫 번째 JDBC Statement 실행 직전
          → 실제 DataSource.getConnection() 호출
          → determineCurrentLookupKey()   ← 이 시점에는 readOnly 플래그가 이미 설정됨 ✓
```

`TenantJpaConfig` 에서 이미 `LazyConnectionDataSourceProxy` 로 `RoutingDataSource` 를
감싸고 있으므로 (ADR 008 참고), 별도 추가 작업 없이 readOnly 라우팅이 정확하게 동작한다.

---

### 4. 도메인 및 영속 계층 — slave 접속 정보 저장

slave 접속 정보를 `Tenant` 애그리거트에 포함시켜 재기동 · 재활성화 시에도 복원되도록 한다.

```
[변경 전]
Tenant { id, dataSourceSpec, status, createdAt }

[변경 후]
Tenant { id, dataSourceSpec(master), slaveDataSourceSpec(nullable), status, createdAt }
```

`TenantJpaEntity` 에 `slave_url`, `slave_username`, `slave_password` 컬럼을 추가한다.
기존 DB 호환성을 위해 `schema.sql` 에 `ALTER TABLE ... ADD COLUMN IF NOT EXISTS` 를 추가한다.

```sql
-- 기존 테이블에 slave 컬럼 추가 (IF NOT EXISTS — 멱등)
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_url      VARCHAR(255);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_username VARCHAR(255);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_password VARCHAR(255);
```

---

### 5. API — slave 선택적 수락

`RegisterTenantRequest` 에 선택적 `slave` 필드를 추가한다.

```json
// slave 없이 등록 (master 단독 — 기존 방식 그대로)
{
  "tenantId": "tenant-a",
  "url": "jdbc:postgresql://localhost:5433/tenant_a",
  "username": "tenant_a",
  "password": "tenant_a"
}

// slave 포함 등록 (읽기 부하 분산)
{
  "tenantId": "tenant-a",
  "url": "jdbc:postgresql://localhost:5433/tenant_a",
  "username": "tenant_a",
  "password": "tenant_a",
  "slave": {
    "url": "jdbc:postgresql://localhost:5435/tenant_a_slave",
    "username": "tenant_a",
    "password": "tenant_a"
  }
}
```

`RegisterTenantCommand` 에 4인수 호환 생성자를 유지하여 기존 통합 테스트 코드를 수정 없이 사용한다.

```java
public record RegisterTenantCommand(
        String tenantId, String url, String username, String password,
        SlaveSpec slave  // null 이면 slave 미구성
) {
    // 기존 4인수 호환 생성자
    public RegisterTenantCommand(String tenantId, String url, String username, String password) {
        this(tenantId, url, username, password, null);
    }
}
```

---

### 6. TenantDataSourceAdapter — slave 드레인

테넌트 정지 시 master 와 slave 풀을 모두 graceful drain 한다.

```java
// 변경 전
public record UnregisterResult(Map<String, DataSource> snapshot, DataSource removed) {}

// 변경 후
public record UnregisterResult(Map<String, DataSource> snapshot, List<DataSource> removed) {}
```

드레인 루프에서 master + slave 풀의 활성 커넥션 수를 합산하여 종료 조건을 판별한다.

```java
int dbConns = pools.stream().mapToInt(this::activeConnections).sum();
```

스키마 초기화(`TenantSchemaInitializer`)는 master 풀에만 실행한다.
slave 는 master 의 복제본이므로 별도 DDL 이 불필요하다.

---

### 라우팅 전체 흐름

```
HTTP 요청 (X-Tenant-Id: tenant-a)
  → TenantInterceptor
  → TenantContextHolder.setTenant("tenant-a")
  │
  ├─ DemoService.save()   @Transactional("tenantTM")
  │    └─ LazyConnectionDataSourceProxy
  │         └─ 첫 SQL 실행 시 determineCurrentLookupKey()
  │              isReadOnly = false
  │              → "tenant-a:master" → master HikariPool → tenant_a DB (쓰기)
  │
  └─ DemoService.findAll() @Transactional(value="tenantTM", readOnly=true)
       └─ LazyConnectionDataSourceProxy
            └─ 첫 SQL 실행 시 determineCurrentLookupKey()
                 isReadOnly = true, "tenant-a:slave" 키 존재 여부 확인
                 → slave 있음: "tenant-a:slave" → slave HikariPool → tenant_a_slave DB (읽기)
                 → slave 없음: "tenant-a:master" → master HikariPool → tenant_a DB (fallback)
```

## 후보 비교

### 후보 1: RoutingDataSource 에 TenantDataSourceRegistry 주입 (채택하지 않음)

```java
public class RoutingDataSource extends AbstractRoutingDataSource {
    private final TenantContextHolder contextHolder;
    private final TenantDataSourceRegistry registry; // 추가

    @Override
    protected Object determineCurrentLookupKey() {
        String tenantId = contextHolder.getTenant().value();
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        if (isReadOnly && registry.hasSlaveRegistered(tenantId)) {
            return tenantId + ":slave";
        }
        return tenantId + ":master";
    }
}
```

- **장점**: registry 가 진실 공급원이 되어 `registeredKeys` 의 중복 관리가 없다.
- **단점**: `RoutingDataSource` 가 `TenantDataSourceRegistry` 에 직접 의존하는 양방향 의존이 생긴다.
  `TenantDataSourceAdapter` → `TenantDataSourceRegistry` → (라우팅 결정에 사용) 의 흐름이 섞인다.
  또한 `RoutingDataSource` 는 Spring Context 외부(단위 테스트)에서도 독립적으로 사용되는데,
  레지스트리를 함께 주입해야 하는 부담이 생긴다.
- **채택하지 않은 이유**: `registeredKeys` 를 `refresh()` 시 동기화하면 외부 의존 없이
  동일한 보장을 제공할 수 있다. `RoutingDataSource` 가 자기 상태만으로 라우팅을 결정하는 편이
  단순하고 테스트하기 쉽다.

### 후보 2: AbstractRoutingDataSource 의 기본 DataSource 활용 (채택하지 않음)

```java
// slave 키를 반환하고, 없을 때 setDefaultTargetDataSource(master) 로 fallback 처리
routingDataSource.setDefaultTargetDataSource(masterDataSource);
```

`AbstractRoutingDataSource` 의 `lenientFallback = true` + default DataSource 를 활용하면
키를 찾지 못할 때 자동으로 default 로 fallback 된다.

- **장점**: slave 존재 여부 추적 코드가 불필요하다.
- **단점**: `setDefaultTargetDataSource` 는 단일 DataSource 를 받으므로 테넌트별 master 를
  default 로 지정할 수 없다. 모든 테넌트가 동일한 default DataSource 를 공유하게 된다.
  또한 fallback 이 발생해도 로그가 남지 않아 slave 미구성과 키 오류를 구분하기 어렵다.
- **채택하지 않은 이유**: 테넌트가 여럿이므로 단일 default DataSource 패턴이 맞지 않는다.

### 후보 3: DataSource 래퍼로 master/slave 를 묶기 (채택하지 않음)

각 테넌트를 위한 `ReadWriteRoutingDataSource` 를 별도 구현하여, master/slave 선택을 내부에서 처리한다.

```java
// 테넌트별 래퍼
class TenantReadWriteDataSource implements DataSource {
    private final DataSource master;
    private final DataSource slave; // nullable

    @Override
    public Connection getConnection() {
        boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
        return readOnly && slave != null ? slave.getConnection() : master.getConnection();
    }
}
```

`TenantDataSourceRegistry` 는 `"tenant-a"` 키 하나에 이 래퍼를 등록한다.

- **장점**: 기존 단일 키 체계를 유지할 수 있다. 기존 테스트 변경이 없다.
- **단점**: `TenantReadWriteDataSource` 라는 추가 추상화 계층이 생긴다.
  HikariCP 의 통계·모니터링(`HikariPoolMXBean`)을 래퍼 안에 숨기므로 Micrometer Gauge 연동이 복잡해진다.
  Graceful drain 시 래퍼를 통해 내부 풀에 접근하는 코드가 필요하다.
- **채택하지 않은 이유**: 복합 키 방식이 같은 보장을 더 단순한 코드로 제공한다.
  추가 추상화 없이 `RoutingDataSource` 의 기존 인터페이스만 확장하면 된다.

### 후보 4: 라우팅 결정을 서비스 계층에서 명시 (채택하지 않음)

`DemoService` 가 직접 두 개의 UseCase 또는 `DataSource` 를 주입받아 선택한다.

```java
@Service
public class DemoService {
    private final DemoMessagePort masterPort;
    private final DemoMessagePort slavePort;   // readOnly 전용

    public List<DemoMessageResult> findAll() {
        return slavePort.findAll()...;
    }
}
```

- **장점**: 어떤 DB 를 사용하는지 코드에서 명시적으로 드러난다.
- **단점**: 모든 서비스가 master/slave 두 개의 포트를 주입받아야 한다. slave 가 없는 테넌트를 처리하는
  분기 로직이 서비스 계층으로 침투한다. `@Transactional(readOnly = true)` 의 의미가 이중으로 표현된다.
- **채택하지 않은 이유**: 인프라 관심사가 응용 계층으로 새어나온다. Spring 의 트랜잭션 추상화를
  활용하면 이 문제를 인프라 계층에서 투명하게 해결할 수 있다.

## 결과

### 장점

- **투명한 라우팅**: 서비스 계층 코드 변경 없이 slave 라우팅이 동작한다. 기존 `@Transactional(readOnly = true)` 선언이 그대로 라우팅 힌트로 활용된다.
- **점진적 도입**: slave 없이 등록한 테넌트는 기존과 동일하게 master 로 라우팅된다. slave 도입이 즉각적인 인프라 변경 없이 테넌트별로 순차 적용 가능하다.
- **하위 호환**: 기존 4인수 `RegisterTenantCommand` 생성자가 유지되어 기존 통합 테스트를 수정 없이 사용할 수 있다.
- **안전한 Graceful drain**: 테넌트 정지 시 master · slave 두 풀이 모두 안전하게 종료된다.
- **LazyConnectionDataSourceProxy 재활용**: ADR 008 에서 기동 시 예외 방지를 위해 이미 적용된 `LazyConnectionDataSourceProxy` 가 readOnly 라우팅 타이밍 문제도 함께 해결한다. 별도 추가 없이 두 문제를 한 번에 처리한다.

### 단점 / 트레이드오프

- **slave 동기화 책임 없음**: 이 구현은 slave 가 master 와 동기화되어 있다고 가정한다.
  복제 지연(replication lag)으로 인한 읽기 불일치는 애플리케이션 계층에서 처리하지 않는다.
  최신 데이터가 반드시 필요한 읽기 연산에는 `@Transactional` (readOnly 없음)을 선언하거나
  master 를 명시적으로 타겟해야 한다.

- **slave DB 스키마 관리**: `TenantSchemaInitializer` 는 master DB 에만 DDL 을 실행한다.
  slave DB 의 스키마는 복제 또는 별도 초기화로 관리해야 한다.
  현재 docker-compose 의 slave 컨테이너는 스키마가 수동으로 생성되거나 복제되어야 동작한다.

- **커넥션 풀 수 증가**: slave 등록 시 테넌트당 HikariCP 풀이 1개에서 2개로 늘어난다.
  ADR 003 의 커넥션 수 계산에 slave 풀을 추가로 고려해야 한다.

  ```
  [slave 포함 시]
  총 커넥션 수 = 테넌트 수 × (masterPoolSize + slavePoolSize)

  테넌트 10개, poolSize=5 × 2 → 최대 100개 커넥션
  ```

- **`readOnly` 에만 의존하는 라우팅 한계**: `@Transactional(readOnly = true)` 없이 직접 `Connection.setReadOnly(true)` 를 호출하거나 트랜잭션 외부에서 실행되는 쿼리는 slave 로 라우팅되지 않는다.

## 테스트 전략

| 테스트 | 클래스 | 크기 | 검증 내용 |
|--------|--------|------|-----------|
| **slave 미구성 — readOnly → master** | `RoutingDataSourceMasterSlaveTest.WithoutSlave` | Small | slave 없을 때 readOnly 트랜잭션도 master 키 반환 |
| **slave 구성 — readOnly → slave** | `RoutingDataSourceMasterSlaveTest.WithSlave` | Small | slave 있을 때 readOnly 트랜잭션이 slave 키 반환 |
| **slave 구성 — 쓰기 → master** | `RoutingDataSourceMasterSlaveTest.WithSlave` | Small | readOnly 아닌 트랜잭션은 slave 있어도 master 키 반환 |
| **refresh 후 키 갱신** | `RoutingDataSourceMasterSlaveTest` | Small | refresh 전/후 라우팅 결과가 즉시 전환됨 |
| **컨텍스트 없음 — 예외** | `RoutingDataSourceMasterSlaveTest` | Small | 테넌트 컨텍스트 없을 때 `TenantContextMissingException` |
| **기존 동시성 안전성** | `TenantDataSourceAdapterConcurrencyTest` | Small | 복합 키 체계에서도 race condition 방지 유지 |
| **라우팅 통합** | `TenantRoutingIntegrationTest` | Large | 기존 테넌트 등록·정지·재활성화 동작 유지 |

### `RoutingDataSourceMasterSlaveTest` 설계 원칙

`TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)` 를 직접 호출하여
실제 Spring 트랜잭션 없이도 `isReadOnly` 분기를 검증한다.

```java
TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
try {
    assertThat(routing.lookupKey()).isEqualTo("tenant-a:slave");
} finally {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); // 반드시 복원
}
```

Spring `TransactionSynchronizationManager` 는 `ThreadLocal` 기반이므로,
테스트 후 `false` 로 복원하지 않으면 같은 스레드에서 실행되는 이후 테스트에 영향을 미친다.
`finally` 블록에서 반드시 복원한다.

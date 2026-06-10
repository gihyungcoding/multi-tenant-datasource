# ADR 009: Master/Slave DataSource 라우팅 — 복합 키 + TransactionSynchronizationManager + Round-Robin

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
- **slave 를 여러 개** 구성할 수 있으며, 읽기 요청을 **round-robin** 으로 분산한다.
- slave 미구성 테넌트는 기존과 동일하게 master 단독으로 동작한다 (하위 호환 보장).
- 라우팅 결정이 응용 코드에 노출되지 않아야 한다. 서비스 계층은 여전히 `@Transactional` 어노테이션만 선언한다.

slave 는 **PostgreSQL Streaming Replication** 만 지원한다.
WAL 스트림이 master 의 DDL 을 포함한 모든 변경사항을 replica 에 자동 전파하므로,
애플리케이션은 slave 에 DDL 을 직접 실행하지 않는다.

## 결정

네 가지 변경을 조합하여 Master/Slave 라우팅을 구현한다.

---

### 1. 복합 키(Composite Key) 체계 — ordinal 인덱스 포함

`TenantDataSourceRegistry` 의 내부 맵 키를 변경한다.

```
[이전 키 체계]
dataSourceMap: { "tenant-a" → HikariDataSource }

[신규 키 체계]
dataSourceMap: {
    "tenant-a:master"  → HikariDataSource (master)
    "tenant-a:slave:0" → HikariDataSource (slave #0, 선택)
    "tenant-a:slave:1" → HikariDataSource (slave #1, 선택)
    ...
}
```

- slave 는 옵션이다. 등록하지 않으면 `"tenant-a:slave:*"` 키가 맵에 존재하지 않는다.
- ordinal (`0`, `1`, …)은 등록 순서와 일치하며, 재기동 후에도 `tenant_replica.ordinal` 컬럼으로 순서를 보장한다.

유틸 메서드:
```java
static String masterKey(TenantId id)         { return id.value() + ":master"; }
static String slaveKey(TenantId id, int idx) { return id.value() + ":slave:" + idx; }
static String slavePrefix(TenantId id)       { return id.value() + ":slave:"; }
```

---

### 2. RoutingDataSource — slave 그룹 추적 + readOnly round-robin

```java
public class RoutingDataSource extends AbstractRoutingDataSource {

    private final TenantContextHolder contextHolder;

    /** refresh() 호출 시 갱신. { tenantId → [slave 키 목록] } */
    private volatile Map<String, List<String>> slaveKeysByTenant = Map.of();

    /** 테넌트별 round-robin 카운터. AtomicLong 으로 스레드 안전. */
    private final ConcurrentHashMap<String, AtomicLong> slaveCounters = new ConcurrentHashMap<>();

    @Override
    protected Object determineCurrentLookupKey() {
        String tenantId = contextHolder.getTenant().value();
        boolean isReadOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();

        if (isReadOnly) {
            List<String> slaveKeys = slaveKeysByTenant.getOrDefault(tenantId, List.of());
            if (!slaveKeys.isEmpty()) {
                AtomicLong counter = slaveCounters.computeIfAbsent(tenantId, k -> new AtomicLong(0));
                int idx = (int) (Math.abs(counter.getAndIncrement()) % slaveKeys.size());
                return slaveKeys.get(idx);   // round-robin 으로 slave 선택
            }
        }
        return tenantId + ":master"; // master 로 라우팅 (기본)
    }

    public void refresh(Map<String, DataSource> dataSourceMap) {
        // slave 키 그룹 사전 계산
        Map<String, List<String>> byTenant = new HashMap<>();
        for (String key : dataSourceMap.keySet()) {
            int slaveIdx = key.indexOf(":slave:");
            if (slaveIdx >= 0) {
                String tid = key.substring(0, slaveIdx);
                byTenant.computeIfAbsent(tid, k -> new ArrayList<>()).add(key);
            }
        }
        // ordinal 순서 보장을 위해 정렬 후 불변 리스트로 교체
        byTenant.replaceAll((k, v) -> List.copyOf(v.stream().sorted().toList()));
        this.slaveKeysByTenant = Map.copyOf(byTenant);
        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
```

**`slaveKeysByTenant` 를 `volatile Map` 으로 관리하는 이유**:
- `AbstractRoutingDataSource.resolvedDataSources` 는 `private` 이므로 직접 접근 불가.
- `refresh()` 호출 시 새 불변 맵으로 교체(`volatile` write)하므로 읽기 스레드는 항상 완전한 스냅숏을 본다.

**round-robin 전략**:
- `AtomicLong.getAndIncrement()` + `% slaveKeys.size()` 로 단순하고 스레드 안전한 분산.
- `Math.abs()` 로 `Long.MIN_VALUE` 오버플로 방어.
- slave 가 추가/제거될 때 카운터를 초기화하지 않아도 된다. 순간적인 분산 편차는 허용 범위.

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

### 4. 도메인 및 영속 계층 — 다중 slave 접속 정보 저장

하나의 테넌트가 0..N 개의 slave 를 가질 수 있으므로,
slave 접속 정보를 `tenant` 테이블의 컬럼 대신 **별도 `tenant_replica` 테이블** 에 저장한다.

```
[도메인 모델]
Tenant {
    id: TenantId
    dataSourceSpec: DataSourceSpec          // master
    slaveSpecs: List<DataSourceSpec>        // slave 0..N (Streaming Replica)
    status: TenantStatus
    createdAt: LocalDateTime
}
```

```sql
-- tenant_replica 테이블 (1:N with tenant)
CREATE TABLE IF NOT EXISTS tenant_replica (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  VARCHAR(255) NOT NULL,
    ordinal    INTEGER      NOT NULL,   -- 0-based, round-robin 순서 보장
    url        VARCHAR(255) NOT NULL,
    username   VARCHAR(255) NOT NULL,
    password   VARCHAR(255) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_tenant_replica_tenant_id ON tenant_replica(tenant_id);
```

`ordinal` 컬럼:
- 등록 순서를 DB 에 기록하므로 재기동 후에도 동일한 round-robin 순서를 재현한다.
- `tenant_replica` 조회 시 `ORDER BY ordinal` 을 보장한다.

저장 패턴 (Replace-on-save):
```java
// TenantPersistenceAdapter.save()
replicaRepository.deleteAllByTenantId(entity.getTenantId());
replicaRepository.saveAll(toReplicaEntities(tenant));
```

slave 접속 정보를 별도 테이블에 두는 목적은 **DataSource 생명주기 관리**다.
slave HikariPool 을 생성·종료하려면 접속 정보가 필요하고,
앱 재기동 시 복원(`TenantDataSourceInitializer`)도 이 정보를 기반으로 한다.
slave 스키마는 Streaming Replication 이 담당하므로 애플리케이션은 스키마 초기화를 수행하지 않는다.

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
  │              → "tenant-a:master" → master HikariPool → tenant_a (쓰기)
  │
  └─ DemoService.findAll() @Transactional(value="tenantTM", readOnly=true)
       └─ LazyConnectionDataSourceProxy
            └─ 첫 SQL 실행 시 determineCurrentLookupKey()
                 isReadOnly = true
                 slaveKeys = ["tenant-a:slave:0", "tenant-a:slave:1"]
                 → slave 있음: round-robin → slave:0 또는 slave:1 → Streaming Replica (읽기)
                 → slave 없음: "tenant-a:master" → master HikariPool (fallback)
```

---

### dev/test 환경 — docker-compose Streaming Replication 구성

`bitnami/postgresql` 이미지의 `POSTGRESQL_REPLICATION_MODE` 환경변수를 이용해
docker-compose 만으로 Streaming Replication 을 구성한다.

```yaml
# master — WAL 스트림 생성
tenant-a-db:
  image: bitnami/postgresql:16
  environment:
    POSTGRESQL_USERNAME: tenant_a
    POSTGRESQL_PASSWORD: tenant_a
    POSTGRESQL_DATABASE: tenant_a
    POSTGRESQL_REPLICATION_MODE: master
    POSTGRESQL_REPLICATION_USER: replicator
    POSTGRESQL_REPLICATION_PASSWORD: replicator_pass

# replica — master 의 WAL 스트림을 수신
tenant-a-slave-db:
  image: bitnami/postgresql:16
  environment:
    POSTGRESQL_REPLICATION_MODE: slave
    POSTGRESQL_REPLICATION_USER: replicator
    POSTGRESQL_REPLICATION_PASSWORD: replicator_pass
    POSTGRESQL_MASTER_HOST: tenant-a-db
    POSTGRESQL_USERNAME: tenant_a
    POSTGRESQL_PASSWORD: tenant_a
  depends_on:
    tenant-a-db:
      condition: service_healthy
```

`pg_basebackup` 을 통한 초기 데이터 복사와 WAL 수신 설정이 자동으로 처리된다.
master 에서 `CREATE TABLE` 이 실행되면 WAL 이 replica 로 전파되므로
앱 기동 후 master 스키마 초기화만 수행하면 slave 도 동일한 스키마를 갖게 된다.

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
            return tenantId + ":slave:0"; // 단일 slave 가정
        }
        return tenantId + ":master";
    }
}
```

- **장점**: registry 가 진실 공급원이 되어 `slaveKeysByTenant` 의 중복 관리가 없다.
- **단점**: `RoutingDataSource` 가 `TenantDataSourceRegistry` 에 직접 의존하는 양방향 의존이 생긴다.
  `RoutingDataSource` 단위 테스트에서 레지스트리를 함께 주입해야 하는 부담이 생긴다.
  round-robin 카운터를 레지스트리 또는 RoutingDataSource 어디에 두어야 할지 책임이 불분명해진다.
- **채택하지 않은 이유**: `slaveKeysByTenant` 를 `refresh()` 시 동기화하면 외부 의존 없이 동일한 보장을 제공한다.
  `RoutingDataSource` 가 자기 상태만으로 라우팅을 결정하는 편이 단순하고 테스트하기 쉽다.

### 후보 2: AbstractRoutingDataSource 의 기본 DataSource 활용 (채택하지 않음)

`AbstractRoutingDataSource` 의 `lenientFallback = true` + default DataSource 를 활용하면
키를 찾지 못할 때 자동으로 default 로 fallback 된다.

- **장점**: slave 존재 여부 추적 코드가 불필요하다.
- **단점**: `setDefaultTargetDataSource` 는 단일 DataSource 를 받으므로 테넌트별 master 를
  default 로 지정할 수 없다. 모든 테넌트가 동일한 default DataSource 를 공유하게 된다.
- **채택하지 않은 이유**: 테넌트가 여럿이므로 단일 default DataSource 패턴이 맞지 않는다.

### 후보 3: DataSource 래퍼로 master/slave 를 묶기 (채택하지 않음)

각 테넌트를 위한 `ReadWriteRoutingDataSource` 를 별도 구현하여, master/slave 선택을 내부에서 처리한다.

- **장점**: 기존 단일 키 체계를 유지할 수 있다.
- **단점**: HikariCP 의 통계·모니터링(`HikariPoolMXBean`)을 래퍼 안에 숨기므로 Micrometer Gauge
  연동과 Graceful drain 이 복잡해진다. round-robin 상태를 래퍼마다 독립적으로 관리해야 한다.
- **채택하지 않은 이유**: 복합 키 방식이 같은 보장을 더 단순한 코드로 제공한다.

### 후보 4: 라우팅 결정을 서비스 계층에서 명시 (채택하지 않음)

`DemoService` 가 직접 두 개의 포트를 주입받아 선택한다.

- **단점**: 인프라 관심사가 응용 계층으로 새어나온다. slave 가 없는 테넌트를 처리하는 분기 로직이
  서비스 계층으로 침투한다. `@Transactional(readOnly = true)` 의 의미가 이중으로 표현된다.
- **채택하지 않은 이유**: Spring 의 트랜잭션 추상화를 활용하면 인프라 계층에서 투명하게 해결할 수 있다.

## 결과

### 장점

- **투명한 라우팅**: 서비스 계층 코드 변경 없이 slave 라우팅이 동작한다. 기존 `@Transactional(readOnly = true)` 선언이 그대로 라우팅 힌트로 활용된다.
- **다중 slave + round-robin**: slave 를 N 개 등록하면 readOnly 트랜잭션이 자동으로 round-robin 분산된다. slave 추가·제거가 `refresh()` 한 번으로 즉시 반영된다.
- **점진적 도입**: slave 없이 등록한 테넌트는 기존과 동일하게 master 로 라우팅된다. slave 도입이 즉각적인 인프라 변경 없이 테넌트별로 순차 적용 가능하다.
- **하위 호환**: 기존 4인수 `RegisterTenantCommand` 생성자가 유지되어 기존 통합 테스트를 수정 없이 사용할 수 있다.
- **안전한 Graceful drain**: 테넌트 정지 시 master · slave 전체 풀이 모두 안전하게 종료된다.
- **LazyConnectionDataSourceProxy 재활용**: ADR 008 에서 기동 시 예외 방지를 위해 이미 적용된 `LazyConnectionDataSourceProxy` 가 readOnly 라우팅 타이밍 문제도 함께 해결한다.
- **스키마 관리 단순화**: slave 를 Streaming Replica 로 한정하면 애플리케이션이 slave 스키마를 관리할 필요가 없다. master DDL 이 WAL 로 자동 전파되므로 `TenantSchemaInitializer` 는 master 초기화만 담당한다.

### 단점 / 트레이드오프

- **slave 동기화 책임 없음**: 이 구현은 slave 가 master 와 동기화되어 있다고 가정한다.
  복제 지연(replication lag)으로 인한 읽기 불일치는 애플리케이션 계층에서 처리하지 않는다.
  최신 데이터가 반드시 필요한 읽기 연산에는 `@Transactional` (readOnly 없음)을 사용해야 한다.

- **커넥션 풀 수 증가**: slave 등록 시 테넌트당 HikariCP 풀이 `1 + slave 수` 로 늘어난다.
  ADR 003 의 커넥션 수 계산에 slave 풀을 추가로 고려해야 한다.

  ```
  [slave N개 포함 시]
  총 커넥션 수 = 테넌트 수 × (masterPoolSize + slavePoolSize × N)

  테넌트 10개, poolSize=5, slave 2개 → 최대 150개 커넥션
  ```

- **`readOnly` 에만 의존하는 라우팅 한계**: `@Transactional(readOnly = true)` 없이 직접
  `Connection.setReadOnly(true)` 를 호출하거나 트랜잭션 외부에서 실행되는 쿼리는
  slave 로 라우팅되지 않는다.

- **Streaming Replica 전용 제약**: 독립 PostgreSQL 인스턴스를 slave 로 사용하는 시나리오를
  지원하지 않는다. slave 스키마를 애플리케이션이 초기화하지 않기 때문이다.

## 테스트 전략

| 테스트 | 클래스 | 크기 | 검증 내용 |
|--------|--------|------|-----------|
| **slave 미구성 — readOnly → master** | `RoutingDataSourceMasterSlaveTest.WithoutSlave` | Small | slave 없을 때 readOnly 트랜잭션도 master 키 반환 |
| **slave 1개 — readOnly → slave:0** | `RoutingDataSourceMasterSlaveTest.WithOneSlave` | Small | slave 1개일 때 readOnly 트랜잭션이 slave:0 키 반환 |
| **slave 2개 — round-robin 분배** | `RoutingDataSourceMasterSlaveTest.WithTwoSlaves` | Small | 6회 호출 시 slave:0, slave:1 각 3회 균등 분배 |
| **slave 구성 — 쓰기 → master** | `RoutingDataSourceMasterSlaveTest.With*Slave` | Small | readOnly 아닌 트랜잭션은 slave 있어도 master 키 반환 |
| **refresh 후 키 갱신** | `RoutingDataSourceMasterSlaveTest` | Small | refresh 전/후 라우팅 결과가 즉시 전환됨 |
| **master 초기화 실패 — 예외 전파** | `TenantSchemaInitializerTest.Initialize` | Small | DataSource 접속 실패 시 예외가 전파됨 |
| **기존 동시성 안전성** | `TenantDataSourceAdapterConcurrencyTest` | Small | 복합 키 체계에서도 race condition 방지 유지 |
| **라우팅 통합** | `TenantRoutingIntegrationTest` | Large | 기존 테넌트 등록·정지·재활성화 동작 유지 |

### `RoutingDataSourceMasterSlaveTest` 설계 원칙

`TransactionSynchronizationManager.setCurrentTransactionReadOnly(true)` 를 직접 호출하여
실제 Spring 트랜잭션 없이도 `isReadOnly` 분기를 검증한다.

```java
TransactionSynchronizationManager.setCurrentTransactionReadOnly(true);
try {
    assertThat(routing.lookupKey()).isEqualTo("tenant-a:slave:0");
} finally {
    TransactionSynchronizationManager.setCurrentTransactionReadOnly(false); // 반드시 복원
}
```

Spring `TransactionSynchronizationManager` 는 `ThreadLocal` 기반이므로,
테스트 후 `false` 로 복원하지 않으면 같은 스레드에서 실행되는 이후 테스트에 영향을 미친다.
`finally` 블록에서 반드시 복원한다.

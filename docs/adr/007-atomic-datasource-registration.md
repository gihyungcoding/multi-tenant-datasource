# ADR 007: DataSource 등록의 원자적 처리 — registerAndSnapshot + synchronized

## 상태

채택 (Accepted)

## 컨텍스트

`TenantDataSourceAdapter.register()` 는 테넌트를 등록할 때 두 단계를 순서대로 실행했다.

```java
// 수정 전 (버그 있음)
public void register(TenantId tenantId, DataSourceSpec spec) {
    registry.register(tenantId, spec);               // [1] 레지스트리에 추가
    routingDataSource.refresh(registry.snapshot());  // [2] 라우팅 테이블 갱신
}
```

[1]과 [2] 사이에 다른 스레드가 끼어들 수 있어 다음 race condition 이 발생한다.

```
Thread A: register("tenant-a") → map = {a}
Thread A: snapshot = registry.snapshot() → {a}  ← stale 포착
Thread B: register("tenant-b") → map = {a, b}
Thread B: refresh({a, b}) → 라우팅 테이블 정상 ✓
Thread A: refresh({a})    → 라우팅 테이블 = {a} ✗  (tenant-b 소멸)
```

테넌트 등록이 동시에 발생하면(초기 로딩 시 여러 테넌트를 병렬 등록하거나,
런타임 중 두 관리자가 거의 동시에 테넌트를 추가하는 경우) 일부 테넌트가
라우팅 테이블에서 사라져 `TenantContextMissingException` 또는 잘못된 DataSource 로의
라우팅이 발생할 수 있다.

## 결정

두 계층에서 원자성을 확보한다.

### 계층 1 — TenantDataSourceRegistry.registerAndSnapshot()

등록(put)과 스냅샷 포착(copyOf)을 하나의 `synchronized` 블록에서 실행한다.

```java
public Map<String, DataSource> registerAndSnapshot(TenantId tenantId, DataSourceSpec spec) {
    DataSource ds = createDataSource(tenantId, spec);   // 락 밖에서 생성 (시간 소요)
    synchronized (this) {
        dataSourceMap.put(tenantId.value(), ds);
        return Map.copyOf(dataSourceMap);               // put + copyOf 가 원자적
    }
}
```

- `createDataSource()` (HikariCP 초기화)는 락 밖에서 선행 실행 → 락 보유 시간 최소화.
- `put + Map.copyOf` 는 같은 모니터 안에서 실행되므로 스냅샷에 자신의 등록이 항상 반영됨.

### 계층 2 — TenantDataSourceAdapter.register() synchronized

`registerAndSnapshot()` 호출부터 `routingDataSource.refresh()` 까지를
하나의 임계 영역으로 묶는다.

```java
@Override
public synchronized void register(TenantId tenantId, DataSourceSpec spec) {
    Map<String, DataSource> snapshot = registry.registerAndSnapshot(tenantId, spec);
    routingDataSource.refresh(snapshot);
}
```

#### 계층 1만으로는 부족한 이유

`registerAndSnapshot()` 이 원자적이더라도 `refresh()` 는 여전히 락 밖에 있다.

```
Thread A: registerAndSnapshot("a") = {a}   (synchronized 블록 종료)
Thread B: registerAndSnapshot("b") = {a,b} (synchronized 블록 종료)
Thread B: refresh({a,b})  → 라우팅 정상 ✓
Thread A: refresh({a})    → b 소멸 ✗      (refresh 순서가 보장되지 않음)
```

계층 2의 `synchronized` 가 이 경쟁을 직렬화한다.

```
Thread A: 락 획득 → registerAndSnapshot("a")={a} → refresh({a}) → 락 반환
Thread B: 락 획득 → registerAndSnapshot("b")={a,b} → refresh({a,b}) → 락 반환
                                                       (항상 누적 스냅샷 적용 ✓)
```

직렬화 덕분에 나중에 락을 획득하는 스레드일수록 더 많은 테넌트가 담긴 스냅샷을
얻고, 마지막 `refresh()` 가 가장 완전한 라우팅 테이블을 남긴다.

## 후보 비교

### 후보 1: adapter.register() 에만 synchronized (단순 잠금)

```java
public synchronized void register(TenantId tenantId, DataSourceSpec spec) {
    registry.register(tenantId, spec);
    routingDataSource.refresh(registry.snapshot());
}
```

- **장점**: 구현이 가장 단순하다.
- **단점**: `registry.register()` 내부에서 HikariCP 풀을 생성하는 동안 락을 보유한다.
  기능적으로는 동일하지만, 레지스트리가 스레드 안전성의 주체가 아니라
  어댑터가 모든 책임을 지는 설계가 된다.
- **채택하지 않은 이유**: `TenantDataSourceRegistry` 자체에도 원자적 API 를 제공해
  레지스트리 단독 사용 시에도 안전하게 만드는 편이 낫다고 판단했다.

### 후보 2: registerAndSnapshot() 만 도입 (계층 1만 적용)

앞서 분석한 것처럼, `refresh()` 가 락 밖에 있으면 refresh 순서 경쟁이 남는다.
단독으로는 불충분하다.

### 후보 3: CAS/버전 기반 단조 갱신 (monotonic refresh)

`RoutingDataSource.refresh()` 가 현재 라우팅 테이블보다 항목 수가 많을 때만 적용하는 방식.

```java
public synchronized void refresh(Map<String, DataSource> snapshot) {
    if (snapshot.size() >= currentSize) {
        super.setTargetDataSources(...);
        super.afterPropertiesSet();
        currentSize = snapshot.size();
    }
}
```

- **장점**: 락 보유 시간이 짧다.
- **단점**: 항목 수로만 비교하므로 테넌트 비활성화 시나리오(항목 수 감소)에서 오동작.
  또한 `AbstractRoutingDataSource` 를 더 크게 변경해야 한다.
- **채택하지 않은 이유**: 현재 범위(테넌트 추가만 지원)에서는 과도하게 복잡하다.

## 결과

### 장점

- 동시 테넌트 등록 시 라우팅 테이블에서 테넌트가 사라지는 bug 를 원천 차단한다.
- `TenantDataSourceRegistry` 가 자체적으로 스레드 안전한 API(`registerAndSnapshot`)
  를 제공하므로 레지스트리를 직접 사용하는 코드도 안전하다.
- HikariCP 생성은 여전히 병렬로 가능하다 (`createDataSource()` 는 락 밖에서 실행).

### 단점 / 트레이드오프

- **직렬화 오버헤드**: 동시 테넌트 등록 요청이 많으면 HikariCP 초기화 시간만큼 대기가
  발생한다. 테넌트 등록은 관리자 API 를 통해 드물게 발생하는 연산이므로 실제 운영 영향은
  미미하다.
- **`AbstractRoutingDataSource` 의 내부 상태**: `refresh()` 는 `afterPropertiesSet()` 을
  반복 호출하므로, 빈 생명주기 외부에서의 사용에 대한 Spring 의 공식 지원이 없다.
  현재 구현 방식은 실질적으로 동작하지만, Spring 업그레이드 시 재검토가 필요하다.

## 테스트 전략

`TenantDataSourceAdapterConcurrencyTest` 에서 세 가지 시나리오를 검증한다.

| 테스트 | 목적 | 예상 결과 |
|--------|------|-----------|
| **버그 패턴 문서화** | `routing.refresh(staleSnapshot)` 이 라우팅 테이블을 직접 오염시킴을 확인 | 항상 통과 (characterization test) |
| **순차 등록 검증** | 두 테넌트를 순서대로 등록하면 모두 라우팅 테이블에 존재 | 항상 통과 |
| **동시성 스트레스** | 50개 Virtual Thread 동시 등록 — `registerAndSnapshot()` 에 1ms sleep 을 주입해 race 창 생성 | 수정 후 항상 통과, 수정 전 실패 가능 |

`RaceConditionInducingRegistry` 의 1ms sleep 은 다음 역할을 한다.

- **수정 전(비동기화 adapter)**: 50개 스레드가 sleep 중 동시 실행되어 각자의 stale
  snapshot 으로 `refresh()` 를 경쟁적으로 호출 → 마지막 refresh 의 stale snapshot 이
  승리하면 다수 테넌트 누락.
- **수정 후(synchronized adapter)**: sleep 중에도 adapter 락을 보유하므로 스레드가
  순차 실행 → 각 refresh 는 직전까지 등록된 모든 테넌트를 포함한 누적 스냅샷 적용 → 정확.

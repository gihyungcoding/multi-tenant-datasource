# ADR 003: 테넌트별 HikariCP 커넥션 풀 전략

## 상태

채택 (Accepted)

## 컨텍스트

멀티테넌시 환경에서 각 테넌트는 독립된 데이터베이스를 가진다.
각 DataSource에 대한 커넥션 풀 전략을 결정해야 한다.

테넌트 수가 늘어날수록 커넥션 풀 총량이 선형으로 증가하므로
풀 사이즈 설정이 시스템 안정성에 직접적인 영향을 준다.

## 결정

테넌트별로 독립된 HikariCP 커넥션 풀을 생성하되,
`maximumPoolSize`를 작게 유지하여 전체 커넥션 수를 통제한다.

```java
HikariConfig config = new HikariConfig();
config.setMaximumPoolSize(5);           // 테넌트당 최대 커넥션 수
config.setMinimumIdle(1);               // 유휴 시 최소 유지 커넥션
config.setConnectionTimeout(3000);
config.setPoolName("HikariPool-" + tenantId.value());  // 모니터링용 이름
```

## 후보 비교

### 후보 1. 테넌트별 독립 풀 (채택)

각 테넌트 DataSource가 독립적인 HikariCP 풀을 가진다.

**장점:**
- 테넌트 간 커넥션 경합 없음 — 한 테넌트의 폭발적 트래픽이 다른 테넌트에 영향 없음.
- 테넌트별 풀 모니터링 가능 (`HikariPool-{tenantId}`로 구분).
- 테넌트 비활성화 시 해당 풀만 종료 가능.

**단점:**
- 테넌트 수 × maximumPoolSize만큼 커넥션이 필요.
- 테넌트가 100개라면 최대 500개 커넥션 (poolSize=5 기준).

### 후보 2. 공유 커넥션 풀

모든 테넌트가 단일 풀을 공유하고, 커넥션 획득 후 `SET search_path`로 스키마를 전환.

**문제점:**
- DB 스키마 기반 멀티테넌시에서만 적용 가능.
- 본 프로젝트는 테넌트별 독립 DB 방식이므로 적용 불가.

## 커넥션 수 계산

```
총 커넥션 수 = 테넌트 수 × maximumPoolSize

테넌트 10개,  poolSize=5  → 최대  50개 커넥션
테넌트 50개,  poolSize=5  → 최대 250개 커넥션
테넌트 100개, poolSize=5  → 최대 500개 커넥션
```

PostgreSQL 기본 `max_connections`는 100이므로
테넌트 수와 poolSize를 함께 고려해야 한다.

## 현재 설정의 한계 및 개선 방향

**현재 한계:**
- 모든 테넌트에 동일한 poolSize 적용 — 트래픽 차이를 반영하지 못함.
- 테넌트 수가 크게 늘어나면 DB 커넥션 한계에 도달할 수 있음.

**개선 가능한 방향:**
1. 테넌트 등급별 poolSize 차등 적용 (예: Premium 테넌트는 10, Basic은 2).
2. PgBouncer 등 커넥션 풀러 도입으로 실제 DB 커넥션 수 감소.
3. 테넌트 비활성화 시 풀 종료 및 재활성화 시 재생성 로직 추가.

## 결과

- 테넌트 간 커넥션 격리로 안정성 확보.
- 풀 이름에 tenantId 포함으로 모니터링 용이.
- 소규모(~20 테넌트) 환경에서는 현재 설정으로 충분.
- 테넌트 수 증가 시 PgBouncer 도입을 권장.

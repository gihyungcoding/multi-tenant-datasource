# ADR 000: 멀티테넌시 아키텍처 채택

## 상태

채택 (Accepted)

## 컨텍스트

여러 고객사(테넌트)에게 동일한 서비스를 제공하는 SaaS 플랫폼을 구축해야 한다.
각 고객사는 독립된 데이터를 가지며, 데이터 격리와 보안이 핵심 요구사항이다.

고객사별 서비스 제공 방식에는 크게 세 가지 선택지가 있었다.

## 결정

**테넌트별 독립 데이터베이스** 방식을 채택한다.

테넌트 식별자는 HTTP 요청 헤더(`X-Tenant-Id`)로 전달받고,
`AbstractRoutingDataSource` 기반의 동적 DataSource 라우팅으로 처리한다.

## 멀티테넌시 구현 방식 비교

### 방식 1. 테넌트별 독립 데이터베이스 (채택)

각 테넌트가 완전히 분리된 데이터베이스를 가진다.

```
tenant-a → jdbc:postgresql://host/tenant_a_db
tenant-b → jdbc:postgresql://host/tenant_b_db
tenant-c → jdbc:postgresql://host/tenant_c_db
```

**장점:**
- 완전한 데이터 격리 — 한 테넌트의 데이터가 다른 테넌트에 노출될 가능성 없음.
- 테넌트별 독립적인 스키마 변경, 백업, 복구 가능.
- 한 테넌트의 대용량 쿼리가 다른 테넌트의 성능에 영향 없음.
- 테넌트 계약 종료 시 해당 DB만 삭제하면 완전한 데이터 제거 가능.
- 규제 요구사항(GDPR, 금융, 의료 등) 대응에 유리.

**단점:**
- 테넌트 수 증가 시 DB 인스턴스 관리 비용 증가.
- 커넥션 풀이 테넌트 수에 비례하여 증가 ([ADR 003](003-hikari-pool-per-tenant.md) 참고).
- 스키마 변경 시 모든 테넌트 DB에 마이그레이션 필요.

### 방식 2. 공유 데이터베이스 — 테넌트별 독립 스키마

하나의 DB 인스턴스에 테넌트별 스키마를 분리.

```
shared-db
├── schema: tenant_a → tenant_a.orders, tenant_a.users ...
├── schema: tenant_b → tenant_b.orders, tenant_b.users ...
└── schema: tenant_c → tenant_c.orders, tenant_c.users ...
```

**장점:**
- DB 인스턴스 수가 적어 인프라 비용 절감.
- 스키마 단위로 어느 정도의 격리 제공.

**단점:**
- DB 인스턴스 장애 시 모든 테넌트 영향.
- PostgreSQL의 경우 스키마 수 증가 시 `search_path` 관리 복잡.
- 테넌트 간 스키마 격리가 완전하지 않아 보안 사고 위험.

### 방식 3. 공유 데이터베이스 — 공유 스키마 (Row-level 격리)

모든 테넌트가 동일한 테이블을 공유하고, `tenant_id` 컬럼으로 구분.

```sql
SELECT * FROM orders WHERE tenant_id = 'tenant-a';
```

**장점:**
- 가장 단순한 구조, 인프라 비용 최소.
- 테넌트 추가 시 별도 DB/스키마 생성 불필요.

**단점:**
- 모든 쿼리에 `tenant_id` 조건 누락 시 데이터 유출 — 개발자 실수에 취약.
- 한 테넌트의 대용량 데이터가 전체 테이블 성능에 영향.
- 데이터 격리 규제 요구사항 충족 어려움.
- 테넌트 데이터 완전 삭제 시 대규모 DELETE 필요.

## 방식별 비교 요약

| 항목 | 독립 DB | 독립 스키마 | 공유 스키마 |
|---|---|---|---|
| 데이터 격리 수준 | 최상 | 중간 | 낮음 |
| 인프라 비용 | 높음 | 중간 | 낮음 |
| 테넌트 추가 복잡도 | 높음 | 중간 | 낮음 |
| 성능 격리 | 완전 | 부분 | 없음 |
| 규제 대응 | 우수 | 보통 | 어려움 |
| 적합 테넌트 수 | ~수백 | ~수천 | 수천 이상 |

## 테넌트 식별 방식

테넌트를 식별하는 방법에도 여러 선택지가 있었다.

| 방식 | 예시 | 특징 |
|---|---|---|
| 서브도메인 | `tenant-a.service.com` | URL 변경 없이 자연스럽지만 DNS 관리 필요 |
| URL 경로 | `/tenant-a/api/orders` | 구현 단순하지만 API 경로가 노출됨 |
| 요청 헤더 | `X-Tenant-Id: tenant-a` | API 경로 변경 없이 깔끔, B2B SaaS에 적합 |
| JWT 클레임 | `{ "tenantId": "tenant-a" }` | 인증과 결합, 보안 강화 |

**`X-Tenant-Id` 헤더 방식 채택 이유:**
- API 경로 변경 없이 테넌트 구분 가능.
- 구현이 단순하며 인터셉터에서 일관되게 처리 가능.
- B2B SaaS 환경에서 클라이언트(고객사 서버)가 명시적으로 테넌트를 지정하는 방식이 자연스러움.

## AbstractRoutingDataSource 선택

Spring에서 런타임에 DataSource를 동적으로 선택하는 방법 중 `AbstractRoutingDataSource`를 채택했다.

| 방법 | 특징 |
|---|---|
| `AbstractRoutingDataSource` (채택) | Spring 표준, `@Transactional`과 자연스럽게 통합 |
| 수동 EntityManager 전환 | 설정 복잡, JPA 어노테이션 트랜잭션과 통합 어려움 |
| Hibernate MultiTenancyStrategy | Hibernate 6 이후 API 재편, 공식 지원 불안정 |

```java
public class RoutingDataSource extends AbstractRoutingDataSource {

    @Override
    protected Object determineCurrentLookupKey() {
        if (!contextHolder.hasTenant()) {
            throw new TenantContextMissingException();
        }
        return contextHolder.getTenant().value();  // e.g. "tenant-a"
    }

    // 신규 테넌트 등록 시 라우팅 테이블 갱신
    public void refresh(Map<String, DataSource> dataSourceMap) {
        super.setTargetDataSources(Map.copyOf(dataSourceMap));
        super.afterPropertiesSet();
    }
}
```

DataSource 등록 흐름은 [ADR 001](001-why-smartinitializingsingleton.md), 커넥션 풀 전략은 [ADR 003](003-hikari-pool-per-tenant.md) 참고.

## 결과

- 테넌트 간 완전한 데이터 격리로 보안 및 규제 요구사항 충족.
- 테넌트별 독립적인 DB 운영으로 장애 영향 범위 최소화.
- 커넥션 풀 관리 전략은 [ADR 003](003-hikari-pool-per-tenant.md) 참고.
- 초기화 타이밍 전략은 [ADR 001](001-why-smartinitializingsingleton.md) 참고.

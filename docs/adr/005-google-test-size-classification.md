# ADR 005: Google Small/Medium/Large 테스트 분류 도입

## 상태

채택 (Accepted)

## 컨텍스트

프로젝트가 성장하면서 테스트를 다음 기준으로 구분할 필요성이 생겼다.

- **실행 속도**: 빠른 피드백이 필요한 단위 테스트와 느리지만 신뢰성 높은 통합 테스트를 분리해야 한다.
- **외부 의존성**: DB, 네트워크 등 외부 서비스에 의존하는 테스트와 그렇지 않은 테스트를 구분해야 한다.
- **CI/CD 파이프라인 최적화**: PR 빌드에서는 빠른 테스트만 실행하고, 머지 후 전체 테스트를 실행하는 전략이 필요하다.

일반적인 `@UnitTest` / `@IntegrationTest` 이분법은 그 경계가 모호하다 (예: MockMvc 테스트는 단위인가? 통합인가?).

## 결정

**Google의 *How Google Tests Software* (Mick Whittaker et al., 2012)에서 제안한 Small/Medium/Large 분류를 채택한다.**

### 분류 기준

| 기준 | Small | Medium | Large |
|------|-------|--------|-------|
| 네트워크 I/O | 금지 | localhost만 허용 | 제한 없음 |
| 디스크 I/O | 금지 | 허용 | 허용 |
| 외부 프로세스 (Docker 등) | 금지 | 금지 | 허용 |
| Spring 컨텍스트 | 없음 | 슬라이스 (`@WebMvcTest` 등) | 전체 |
| 대표 실행 시간 | < 100ms | < 5s | 수십 초 |

### 어노테이션 정의

```java
@Tag("small")  @interface SmallTest  {}   // domain, service (Mockito)
@Tag("medium") @interface MediumTest {}   // @WebMvcTest (MockMvc)
@Tag("large")  @interface LargeTest  {}   // @SpringBootTest + Testcontainers
```

### 이 프로젝트의 분류 현황

**Small** (I/O 없음, Spring 없음)

| 클래스 | 설명 |
|--------|------|
| `TenantTest` | Tenant 애그리거트 루트 동작 |
| `TenantIdTest` | 값 객체 유효성 검증 |
| `DataSourceSpecTest` | 값 객체 유효성 검증 |
| `TenantStatusTest` | Sealed Interface 상태 전환 |
| `DemoMessageTest` | `create()` / `restore()` 팩토리 메서드 |
| `TenantContextHolderImplTest` | ThreadLocal 격리, Virtual Thread 100개 동시성 |
| `RegisterTenantServiceTest` | 서비스 로직, Mock Port |
| `GetTenantServiceTest` | 서비스 로직, Mock Port |
| `ResolveTenantDataSourceServiceTest` | 서비스 로직, Mock Port & ContextHolder |
| `DemoServiceTest` | 서비스 로직, Mock Port & ContextHolder |

**Medium** (localhost, Spring WebMvc 슬라이스)

| 클래스 | 설명 |
|--------|------|
| `TenantControllerTest` | MockMvc, Mock UseCase, 검증/예외 HTTP 상태 코드 |
| `DemoControllerTest` | MockMvc, Mock UseCase, 검증/응답 구조 |

**Large** (Testcontainers PostgreSQL 3개)

| 클래스 | 설명 |
|--------|------|
| `TenantRoutingIntegrationTest` | DataSource 라우팅 E2E, 동시성(Virtual Thread 100개) |
| `MultiTenantDatasourceApplicationTests` | Spring 컨텍스트 로드 검증 |

### Gradle 실행 명령

```bash
./gradlew test -Dgroups=small    # Small만 실행 (빠른 피드백, Docker 불필요)
./gradlew test -Dgroups=medium   # Medium만 실행
./gradlew test -Dgroups=large    # Large만 실행 (Docker 필요)
./gradlew test                   # 전체 실행
```

## 결과

### 장점

- PR 빌드에서 Small + Medium(`-Dgroups=small,medium`)만 실행해 빠른 피드백이 가능하다.
- 테스트 작성 시 "이 테스트가 외부 서비스에 의존해야 하는가?"를 명확히 고민하게 되어 테스트 품질이 높아진다.
- Testcontainers가 필요한 Large 테스트를 Docker 없는 환경에서 선택적으로 제외할 수 있다.

### 단점 / 트레이드오프

- 팀원 모두가 분류 기준을 이해하고 준수해야 한다. 규칙 없이 `@SmallTest`를 붙이면 의미가 퇴색된다.
- Medium과 Large의 경계가 모호할 수 있다. 이 프로젝트에서는 **Testcontainers(Docker) 사용 여부**를 Large 기준으로 삼는다.

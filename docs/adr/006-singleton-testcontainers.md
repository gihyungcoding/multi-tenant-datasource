# ADR 006: Singleton Testcontainers 패턴 채택

## 상태

채택 (Accepted)

## 컨텍스트

통합 테스트에서 Testcontainers PostgreSQL 컨테이너 3개(master, tenant-a, tenant-b)를 사용한다.

초기 구현에서 `@Testcontainers` + `@Container` + `@ServiceConnection` 조합을 사용했는데 두 가지 문제가 있었다.

### 문제 1: `@ServiceConnection`이 커스텀 프로퍼티를 채우지 못함

`DataSourceConfig`는 `@ConfigurationProperties("spring.datasource.master")`로 마스터 DB 설정을 읽는다. `@ServiceConnection`은 Spring Boot 표준 DataSource 설정(`spring.datasource.url` 등)에 `ConnectionDetails` 빈을 주입하는 방식으로 동작하므로, 커스텀 프로퍼티 키에는 적용되지 않는다.

### 문제 2: 추상 기반 클래스의 `@Container static` 필드가 테스트 클래스 간 컨테이너를 조기 종료시킴

```
JUnit 5 @Testcontainers 확장이 각 테스트 클래스 완료 시
→ 해당 클래스에서 상속받은 static @Container 필드의 컨테이너를 stop()
→ 두 번째 테스트 클래스 실행 시 이미 종료된 컨테이너에 접근 시도
→ "Connection to localhost:PORT refused" 오류
```

## 결정

**Singleton Testcontainers 패턴**을 적용한다.

```java
public abstract class IntegrationTestBase {

    // @Testcontainers / @Container 어노테이션 없음
    static final PostgreSQLContainer masterDb =
        new PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("master")
            .withUsername("master")
            .withPassword("master");

    static final PostgreSQLContainer tenantADb = ...;
    static final PostgreSQLContainer tenantBDb = ...;

    // JVM 시작 시 1회만 start() — Ryuk이 JVM 종료 시 cleanup
    static {
        masterDb.start();
        tenantADb.start();
        tenantBDb.start();
    }

    @DynamicPropertySource
    static void masterDataSourceProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.master.url",      masterDb::getJdbcUrl);
        registry.add("spring.datasource.master.username", masterDb::getUsername);
        registry.add("spring.datasource.master.password", masterDb::getPassword);
    }
}
```

### 핵심 설계 결정

1. **`@Testcontainers` / `@Container` 제거**: JUnit 5 확장이 컨테이너 생명주기를 관리하지 않는다.
2. **`static {}` 블록으로 1회 시작**: JVM 클래스 로딩 시 단 한 번 `start()`가 호출된다. 이후 모든 테스트 클래스가 동일한 컨테이너 인스턴스를 재사용한다.
3. **Testcontainers Ryuk이 자동 종료 처리**: Ryuk 리퍼 컨테이너가 JVM 종료 시 시작된 컨테이너를 정리한다.
4. **`@DynamicPropertySource`로 커스텀 프로퍼티 주입**: `spring.datasource.master.url/username/password`를 컨테이너의 실제 포트로 동적 설정한다.
5. **`PostgreSQLContainer` 비제네릭 사용**: 최신 Testcontainers에서 `PostgreSQLContainer<?>` 대신 `PostgreSQLContainer` 권장.

## 결과

### 장점

- 컨테이너가 JVM당 1회만 시작되므로 테스트 스위트 전체 실행 시간이 단축된다.
- 테스트 클래스 순서나 개수에 관계없이 안정적으로 동작한다.
- `@DynamicPropertySource`로 임의 포트의 컨테이너 URL을 정확히 주입할 수 있다.

### 단점 / 트레이드오프

- 컨테이너가 테스트 간 공유되므로, 한 테스트가 DB 상태를 변경하면 다른 테스트에 영향을 줄 수 있다.  
  → 각 테스트는 `@BeforeEach`에서 상태를 초기화하거나 독립적인 데이터를 사용해야 한다.
- JVM이 살아있는 동안 컨테이너가 유지되므로 로컬 개발 환경에서 포트가 오래 점유된다.  
  → Testcontainers는 임의 포트를 사용하므로 실제로는 문제가 없다.

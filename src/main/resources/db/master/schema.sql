-- 마스터 DB 스키마 정의
-- 테넌트 메타데이터(연결 정보·상태)를 관리한다.
--
-- [운영 환경 참고]
-- 현재 MasterJpaConfig 는 hbm2ddl.auto=update 를 사용한다.
-- 프로덕션에서는 Flyway/Liquibase 로 마이그레이션을 관리하고
-- hbm2ddl.auto=validate 로 전환할 것을 권장한다.

-- 테넌트 메타데이터 테이블 (master DataSource 접속 정보 + 상태)
CREATE TABLE IF NOT EXISTS tenant (
    tenant_id      VARCHAR(255) NOT NULL,
    url            VARCHAR(255) NOT NULL,    -- master DataSource JDBC URL
    username       VARCHAR(255) NOT NULL,    -- master DB 접속 계정
    password       VARCHAR(255) NOT NULL,    -- master DB 접속 비밀번호 (운영 환경에서는 암호화 필요)
    status         VARCHAR(50)  NOT NULL,    -- 'ACTIVE' | 'SUSPENDED'
    suspend_reason VARCHAR(255),             -- 정지 사유 (status='SUSPENDED' 시 설정)
    created_at     TIMESTAMP    NOT NULL,    -- 등록 시각 (불변)
    PRIMARY KEY (tenant_id)
);

-- Streaming Replica DataSource 목록 (테넌트당 0개 이상)
-- slave 가 여러 개인 경우 round-robin 라우팅을 위해 ordinal(등록 순서)을 보존한다.
CREATE TABLE IF NOT EXISTS tenant_replica (
    id         BIGSERIAL    NOT NULL,
    tenant_id  VARCHAR(255) NOT NULL,    -- tenant.tenant_id 참조 (논리적 FK)
    ordinal    INTEGER      NOT NULL,    -- 0-based 순서 (round-robin 라우팅 기준)
    url        VARCHAR(255) NOT NULL,    -- slave DataSource JDBC URL
    username   VARCHAR(255) NOT NULL,   -- slave DB 접속 계정
    password   VARCHAR(255) NOT NULL,   -- slave DB 접속 비밀번호
    PRIMARY KEY (id)
);

-- 기동 시 findAllByStatus('ACTIVE') 쿼리 최적화
CREATE INDEX IF NOT EXISTS idx_tenant_status    ON tenant         (status);
CREATE INDEX IF NOT EXISTS idx_tenant_replica_tenant_id ON tenant_replica (tenant_id);

-- ── 기존 DB 마이그레이션 (멱등) ────────────────────────────────────────────
-- slave 컬럼을 단일 컬럼으로 관리하던 구 스키마에서 tenant_replica 테이블 방식으로 전환.
-- slave_url/username/password 컬럼이 남아있어도 애플리케이션은 사용하지 않으므로 무해하다.
-- 필요 시 아래 구문으로 컬럼을 제거할 수 있다 (데이터 삭제에 주의).
-- ALTER TABLE tenant DROP COLUMN IF EXISTS slave_url;
-- ALTER TABLE tenant DROP COLUMN IF EXISTS slave_username;
-- ALTER TABLE tenant DROP COLUMN IF EXISTS slave_password;

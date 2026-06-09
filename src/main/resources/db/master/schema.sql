-- 마스터 DB 스키마 정의
-- 테넌트 메타데이터(연결 정보·상태)를 관리한다.
--
-- [운영 환경 참고]
-- 현재 MasterJpaConfig 는 hbm2ddl.auto=update 를 사용한다.
-- 프로덕션에서는 Flyway/Liquibase 로 마이그레이션을 관리하고
-- hbm2ddl.auto=validate 로 전환할 것을 권장한다.

-- 테넌트 메타데이터 테이블
CREATE TABLE IF NOT EXISTS tenant (
    tenant_id      VARCHAR(255) NOT NULL,
    url            VARCHAR(255) NOT NULL,    -- master DataSource JDBC URL
    username       VARCHAR(255) NOT NULL,    -- master DB 접속 계정
    password       VARCHAR(255) NOT NULL,    -- master DB 접속 비밀번호 (운영 환경에서는 암호화 필요)
    slave_url      VARCHAR(255),             -- slave DataSource JDBC URL (null 이면 master 단독 운영)
    slave_username VARCHAR(255),             -- slave DB 접속 계정
    slave_password VARCHAR(255),             -- slave DB 접속 비밀번호
    status         VARCHAR(50)  NOT NULL,    -- 'ACTIVE' | 'SUSPENDED'
    suspend_reason VARCHAR(255),             -- 정지 사유 (status='SUSPENDED' 시 설정)
    created_at     TIMESTAMP    NOT NULL,    -- 등록 시각 (불변)
    PRIMARY KEY (tenant_id)
);

-- 기존 테이블에 slave 컬럼 추가 (IF NOT EXISTS — 멱등)
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_url      VARCHAR(255);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_username VARCHAR(255);
ALTER TABLE tenant ADD COLUMN IF NOT EXISTS slave_password VARCHAR(255);

-- 기동 시 findAllByStatus('ACTIVE') 쿼리 최적화
CREATE INDEX IF NOT EXISTS idx_tenant_status ON tenant (status);

-- 테넌트 DB 스키마 초기화 스크립트
-- TenantSchemaInitializer 가 테넌트 등록 시점에 실행한다.
-- IF NOT EXISTS 를 사용하므로 재기동 시 멱등하게 동작한다.

-- 1. demo_message PK 시퀀스 (demo_message 테이블보다 먼저 생성해야 함)
CREATE SEQUENCE IF NOT EXISTS demo_message_seq
    START WITH 1
    INCREMENT BY 1;

-- 2. 데모 메시지 테이블 (DemoMessageJpaEntity 에 대응)
CREATE TABLE IF NOT EXISTS demo_message (
    id         BIGINT       NOT NULL DEFAULT nextval('demo_message_seq'),
    tenant_id  VARCHAR(255) NOT NULL,
    content    VARCHAR(255) NOT NULL,
    created_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (id)
);

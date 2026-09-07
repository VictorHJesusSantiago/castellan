



CREATE TABLE process_definitions (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    process_id     VARCHAR(200) NOT NULL,
    version        INT          NOT NULL,
    name           VARCHAR(200),
    bpmn_xml       CLOB         NOT NULL,
    deployed_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_process_definitions_id_version UNIQUE (process_id, version)
);

CREATE TABLE process_instances (
    id                 VARCHAR(64)  PRIMARY KEY,
    process_id         VARCHAR(200) NOT NULL,
    definition_version INT          NOT NULL,
    status             VARCHAR(20)  NOT NULL,
    state_json         CLOB         NOT NULL,
    created_at         TIMESTAMP    NOT NULL,
    updated_at         TIMESTAMP    NOT NULL
);

CREATE TABLE timers (
    instance_id VARCHAR(64) NOT NULL,
    token_id    VARCHAR(64) NOT NULL,
    due_at      TIMESTAMP   NOT NULL,
    PRIMARY KEY (instance_id, token_id)
);
CREATE INDEX ix_timers_due_at ON timers (due_at);

CREATE TABLE rule_sets (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    name           VARCHAR(200) NOT NULL,
    version        INT          NOT NULL,
    rules_json     CLOB         NOT NULL,
    deployed_at    TIMESTAMP    NOT NULL,
    CONSTRAINT uq_rule_sets_name_version UNIQUE (name, version)
);

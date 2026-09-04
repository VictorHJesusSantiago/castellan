CREATE TABLE spans (
    span_id            VARCHAR(64) PRIMARY KEY,
    trace_id           VARCHAR(64) NOT NULL,
    parent_span_id     VARCHAR(64),
    name               VARCHAR(255) NOT NULL,
    kind               VARCHAR(16) NOT NULL,
    status             VARCHAR(16) NOT NULL,
    error_message      VARCHAR(2048),
    error_stack_trace  CLOB,
    start_epoch_millis BIGINT NOT NULL,
    end_epoch_millis   BIGINT NOT NULL,
    duration_nanos     BIGINT NOT NULL,
    attributes_json    CLOB NOT NULL
);

CREATE INDEX idx_spans_trace_id ON spans (trace_id);
CREATE INDEX idx_spans_parent_span_id ON spans (parent_span_id);
CREATE INDEX idx_spans_name ON spans (name);
CREATE INDEX idx_spans_start_epoch_millis ON spans (start_epoch_millis);

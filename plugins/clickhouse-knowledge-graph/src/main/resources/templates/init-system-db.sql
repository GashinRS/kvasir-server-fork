CREATE DATABASE IF NOT EXISTS {cfg.database};

CREATE TABLE IF NOT EXISTS {cfg.database}.pods (
    id LowCardinality (String),
    configuration String,
    write_ts DateTime64 (3),
) ENGINE = ReplacingMergeTree()
    ORDER BY (id, write_ts);
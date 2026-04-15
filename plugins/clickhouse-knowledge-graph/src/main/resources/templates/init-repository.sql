CREATE DATABASE IF NOT EXISTS `{cfg.database}`;

CREATE TABLE IF NOT EXISTS `{cfg.database}`.{cfg.collectionName} (
    id String,
    write_ts DateTime64 (3) Codec (DoubleDelta, LZ4),
    model_version LowCardinality(String),
    json_representation String
) ENGINE = ReplacingMergeTree()
    ORDER BY (id, write_ts);
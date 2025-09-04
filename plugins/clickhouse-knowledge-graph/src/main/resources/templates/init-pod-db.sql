CREATE DATABASE IF NOT EXISTS {cfg.database};

CREATE TABLE IF NOT EXISTS {cfg.database}.data (
    subject String,
    predicate String,
    object Dynamic,
    datatype LowCardinality(String),
    language LowCardinality(String),
    graph LowCardinality(String),
    timestamp DateTime64(3) Codec (DoubleDelta, LZ4),
    change_request_id String,
    sign  Int8
) ENGINE = ReplacingMergeTree
    PARTITION BY toYYYYMM(timestamp)
    ORDER BY (subject, predicate, object, datatype, language, graph, timestamp, change_request_id, sign);

CREATE TABLE IF NOT EXISTS {cfg.database}.observations (
    id                 String,
    graph              LowCardinality(String),
    series_id          UInt64 Codec (Delta, LZ4),
    change_request_id  String,
    change_request_ts  DateTime64(3) Codec (DoubleDelta, LZ4),
    timestamp          DateTime64(6) Codec (DoubleDelta, LZ4),
    value_number       Float64 Codec (Gorilla, LZ4),
    value_string       String Codec (LZ4HC),
    value_bool         Bool Codec (LZ4),
    value_datatype     LowCardinality(String),
    value_lang         LowCardinality(String),
    labels             Map(LowCardinality(String), String),
    PROJECTION series_id_proj (SELECT * ORDER BY (series_id, timestamp))
) ENGINE = ReplacingMergeTree()
    PARTITION BY toYYYYMM(timestamp)
    ORDER BY (series_id, timestamp, id)
    SETTINGS deduplicate_merge_projection_mode = 'drop';

CREATE TABLE IF NOT EXISTS {cfg.database}.series
(
    label_name_value LowCardinality(String),
    series_id UInt64
) ENGINE = ReplacingMergeTree()
    ORDER BY (label_name_value, series_id);

CREATE MATERIALIZED VIEW IF NOT EXISTS {cfg.database}.series_mv TO {cfg.database}.series AS
SELECT concat (tupleElement(label,1), '=', tupleElement(label, 2)) as label_name_value, series_id
FROM {cfg.database}.observations array join labels as label;

CREATE TABLE IF NOT EXISTS {cfg.database}.metadata (
    type_uri LowCardinality (String),
    property_uri LowCardinality (String),
    property_kind LowCardinality (String),
    property_ref LowCardinality (String)
) ENGINE = ReplacingMergeTree()
    ORDER BY (type_uri, property_uri, property_ref, property_kind);

CREATE TABLE IF NOT EXISTS {cfg.database}.changelog (
    id String,
    requesting_user String,
    pod_id LowCardinality (String),
    status_entry String,
    slice_id String,
    nr_of_inserts Int64,
    nr_of_deletes Int64,
    error_message Nullable(String),
    write_ts DateTime64 (3) Codec (DoubleDelta, LZ4),
) ENGINE = ReplacingMergeTree()
    ORDER BY (slice_id, id, write_ts);

CREATE TABLE IF NOT EXISTS {cfg.database}.slices (
    id String,
    context String,
    author String,
    name String,
    description String,
    schema String,
    supports_changes Bool,
    target_graphs String,
    write_ts DateTime64 (3) Codec (DoubleDelta, LZ4),
) ENGINE = ReplacingMergeTree()
    ORDER BY (id, write_ts);
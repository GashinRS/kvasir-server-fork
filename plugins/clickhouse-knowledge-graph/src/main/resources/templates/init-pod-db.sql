CREATE DATABASE IF NOT EXISTS `{cfg.database}`;

-- Main data table for storing RDF quads (full history)
CREATE TABLE IF NOT EXISTS `{cfg.database}`.data (
    subject String,
    predicate LowCardinality(String),
    object String,
    datatype LowCardinality(String),
    language LowCardinality(String),
    graph LowCardinality(String),
    timestamp DateTime64(3) Codec (DoubleDelta, LZ4),
    change_id String,
    sign  Int8,
    PROJECTION subject_sort (SELECT * ORDER BY subject, predicate, object, datatype, language, graph, change_id, sign),
    INDEX idx_change_id change_id TYPE minmax GRANULARITY 4
) ENGINE = ReplacingMergeTree
    PARTITION BY toYYYYMM(timestamp)
    ORDER BY (predicate, subject, object, datatype, language, graph, change_id, sign)
    SETTINGS deduplicate_merge_projection_mode='rebuild';

-- Data table for storing RDF quads (current state only, with history preserved in the main data table)
CREATE TABLE IF NOT EXISTS `{cfg.database}`.current_data (
    subject String,
    predicate LowCardinality(String),
    object String,
    datatype LowCardinality(String),
    language LowCardinality(String),
    graph LowCardinality(String),
    timestamp DateTime64(3) Codec (DoubleDelta, LZ4),
    change_id String,
    sign  Int8,
    PROJECTION subject_sort (SELECT * ORDER BY subject, predicate, object, datatype, language, graph),
    INDEX idx_change_id change_id TYPE minmax GRANULARITY 4
) ENGINE = ReplacingMergeTree
    PARTITION BY toYYYYMM(timestamp)
    ORDER BY (predicate, subject, object, datatype, language, graph)
    SETTINGS deduplicate_merge_projection_mode='rebuild';

-- Auxiliary table to track the types of subjects for efficient querying and inference
CREATE TABLE IF NOT EXISTS `{cfg.database}`.subject_types (
    subject String,
    type_uri LowCardinality(String),
    graph LowCardinality(String),
    change_id String,
    sign Int8
) ENGINE = ReplacingMergeTree()
    ORDER BY (type_uri, subject, graph, change_id, sign);

-- Auxiliary table to track the current types of subjects for efficient querying and inference, with history preserved in the main subject_types table
CREATE TABLE IF NOT EXISTS `{cfg.database}`.current_subject_types (
    subject String,
    type_uri LowCardinality(String),
    graph LowCardinality(String),
    change_id String,
    sign Int8
) ENGINE = ReplacingMergeTree()
    ORDER BY (type_uri, subject, graph);

-- Metadata table to store inferred schema information about types and properties
CREATE TABLE IF NOT EXISTS `{cfg.database}`.metadata (
    type_uri LowCardinality (String),
    property_uri LowCardinality (String),
    property_kind LowCardinality (String),
    property_ref LowCardinality (String)
) ENGINE = ReplacingMergeTree()
    ORDER BY (type_uri, property_uri, property_ref, property_kind);

-- Insert trigger to populate the metadata table
CREATE MATERIALIZED VIEW IF NOT EXISTS `{cfg.database}`.metadata_mv
TO `{cfg.database}`.metadata
AS
WITH
    -- 1. Identify ALL types defined in this insert block
    subject_definitions AS (
        SELECT
            subject,
            object AS type_uri
        FROM `{cfg.database}`.data
        WHERE predicate = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type'
    ),

    -- 2. Identify ALL properties defined in this insert block
    subject_properties AS (
        SELECT
            change_id, -- Keep this to track provenance
            subject,
            predicate AS property_uri,
            object AS object_value,
            datatype
        FROM `{cfg.database}`.data
        WHERE predicate != 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type'
    )
SELECT DISTINCT
    sd.type_uri as type_uri,
    sp.property_uri as property_uri,
    CASE
        WHEN sp.datatype != '' THEN 'Literal'
        ELSE 'IRI'
    END AS property_kind,
    CASE
        WHEN sp.datatype != '' THEN sp.datatype
        -- Look for the object's type anywhere in the current block
        ELSE COALESCE(
                nullIf(target_sd.type_uri, ''),
                'http://www.w3.org/2000/01/rdf-schema#Resource'
             )
    END AS property_ref
FROM subject_definitions sd
         LEFT JOIN subject_properties sp ON sd.subject = sp.subject
         LEFT JOIN subject_definitions target_sd ON sp.object_value = target_sd.subject;

-- Populate the subject_types and current_subject_types tables using materialized views that listen to inserts on the main data table
CREATE MATERIALIZED VIEW IF NOT EXISTS `{cfg.database}`.subject_types_mv
TO `{cfg.database}`.subject_types
AS
SELECT
    subject,
    object AS type_uri,
    graph,
    change_id,
    sign
FROM `{cfg.database}`.data
WHERE predicate = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';

CREATE MATERIALIZED VIEW IF NOT EXISTS `{cfg.database}`.current_subject_types_mv
TO `{cfg.database}`.current_subject_types
AS
SELECT
    subject,
    object AS type_uri,
    graph,
    change_id,
    sign
FROM `{cfg.database}`.data
WHERE predicate = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type';
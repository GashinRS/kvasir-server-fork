CREATE DATABASE IF NOT EXISTS `{cfg.database}`;

CREATE TABLE IF NOT EXISTS `{cfg.database}`.data (
    subject String,
    predicate String,
    object String,
    datatype LowCardinality(String),
    language LowCardinality(String),
    graph LowCardinality(String),
    timestamp DateTime64(3) Codec (DoubleDelta, LZ4),
    change_id String,
    sign  Int8
) ENGINE = ReplacingMergeTree
    PARTITION BY toYYYYMM(timestamp)
    ORDER BY (subject, predicate, object, datatype, language, graph, change_id, sign);

CREATE TABLE IF NOT EXISTS `{cfg.database}`.metadata (
    type_uri LowCardinality (String),
    property_uri LowCardinality (String),
    property_kind LowCardinality (String),
    property_ref LowCardinality (String)
) ENGINE = ReplacingMergeTree()
    ORDER BY (type_uri, property_uri, property_ref, property_kind);

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
                target_sd.type_uri,
                'http://www.w3.org/2000/01/rdf-schema#Resource'
             )
    END AS property_ref
FROM subject_definitions sd
         LEFT JOIN subject_properties sp ON sd.subject = sp.subject
         LEFT JOIN subject_definitions target_sd ON sp.object_value = target_sd.subject;

CREATE VIEW IF NOT EXISTS `{cfg.database}`.collapsed_state_by_type AS SELECT subject, predicate, object, datatype, language, graph, max(change_id) as _change_id FROM `{cfg.database}`.data
    WHERE
        (\{at_change_id:String\} = '' OR change_id <= \{at_change_id:String\}) AND
        (length(\{domainClassIRIs:Array(String)\}) = 0 OR subject IN (
            SELECT subject FROM `{cfg.database}`.data WHERE (\{at_change_id:String\} = '' OR change_id <= \{at_change_id:String\}) AND (predicate = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type') AND (object IN (\{domainClassIRIs:Array(String)\}))
            GROUP BY subject, predicate, object HAVING argMax(sign, change_id) > 0
        )) AND
        (length(\{rangeClassIRIs:Array(String)\}) = 0 OR object IN (
            SELECT subject FROM `{cfg.database}`.data WHERE (\{at_change_id:String\} = '' OR change_id <= \{at_change_id:String\}) AND (predicate = 'http://www.w3.org/1999/02/22-rdf-syntax-ns#type') AND (object IN (\{rangeClassIRIs:Array(String)\}))
            GROUP BY subject, predicate, object HAVING argMax(sign, change_id) > 0
        ))
    GROUP BY subject, predicate, object, datatype, language, graph HAVING argMax(sign, change_id) > 0;
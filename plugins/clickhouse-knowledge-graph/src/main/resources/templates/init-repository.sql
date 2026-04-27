CREATE DATABASE IF NOT EXISTS `{cfg.database}`;

CREATE TABLE IF NOT EXISTS `{cfg.database}`.{cfg.collectionName} (
    id String,
    revision_id String,
    {#if cfg.versioned}parent_revision_id String,{/if}
    {#if cfg.versioned}branch LowCardinality(String),{/if}
    created_by String,
    model_version LowCardinality(String),
    json_representation String CODEC(ZSTD(3))
) ENGINE = ReplacingMergeTree()
    ORDER BY (id, {#if cfg.versioned}branch,{/if} revision_id);

{#if cfg.versioned}
CREATE TABLE IF NOT EXISTS `{cfg.database}`.{cfg.collectionName}_tags (
    id String,
    tag_name LowCardinality(String),
    revision_id String,
    created_by String,
    created_at DateTime64(3) DEFAULT now64()
) ENGINE = ReplacingMergeTree(created_at)
    ORDER BY (id, tag_name);
{/if}
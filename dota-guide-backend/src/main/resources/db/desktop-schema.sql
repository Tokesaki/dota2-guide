-- ============================================================
-- 本地软件模式（desktop profile）的建表脚本：H2 数据库，MySQL 兼容模式
--
-- 和 sql/init.sql + sql/review.sql 是同一套表，只是去掉了 H2 不认的部分
-- （ENGINE、CHARSET、表注释、CREATE DATABASE / USE）。
-- 改了 MySQL 那边的表结构，记得这里也改一下。
-- ============================================================

CREATE TABLE IF NOT EXISTS hero (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    name       VARCHAR(50)   NOT NULL,
    slug       VARCHAR(64)   NOT NULL DEFAULT '',
    alias      VARCHAR(200)  NOT NULL DEFAULT '',
    `role`     VARCHAR(50)   NOT NULL,
    pos        VARCHAR(20)   NOT NULL DEFAULT '',
    attr       VARCHAR(10)   NOT NULL DEFAULT '',
    attack     VARCHAR(10)   NOT NULL DEFAULT '',
    complexity TINYINT       NOT NULL DEFAULT 1,
    avatar     VARCHAR(200)  NOT NULL DEFAULT '',
    intro      VARCHAR(500)  NOT NULL,
    tips       VARCHAR(1000) NOT NULL,
    items      VARCHAR(500)  NOT NULL DEFAULT '',
    counters   VARCHAR(500)  NOT NULL DEFAULT '',
    PRIMARY KEY (id),
    CONSTRAINT uk_slug UNIQUE (slug)
);

CREATE TABLE IF NOT EXISTS hero_skill (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    hero_id        BIGINT        NOT NULL,
    skill_key      VARCHAR(10)   NOT NULL,
    skill_type     VARCHAR(10)   NOT NULL DEFAULT '主动',
    skill_category VARCHAR(10)   NOT NULL DEFAULT '基础',
    is_innate      TINYINT       NOT NULL DEFAULT 0,
    sort_order     INT           NOT NULL DEFAULT 0,
    skill_name     VARCHAR(50)   NOT NULL,
    skill_desc     VARCHAR(1000) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_hero_order UNIQUE (hero_id, sort_order)
);

CREATE TABLE IF NOT EXISTS od_cache (
    cache_key  VARCHAR(100) NOT NULL,
    body       CLOB         NOT NULL,
    fetched_at TIMESTAMP    NOT NULL,
    PRIMARY KEY (cache_key)
);

CREATE TABLE IF NOT EXISTS meta_scan (
    match_id   BIGINT    NOT NULL,
    scanned_at TIMESTAMP NOT NULL,
    PRIMARY KEY (match_id)
);

CREATE TABLE IF NOT EXISTS meta_build (
    match_id      BIGINT        NOT NULL,
    hero_id       INT           NOT NULL,
    win           TINYINT       NOT NULL,
    avg_rank_tier INT           NOT NULL DEFAULT 0,
    start_time    BIGINT        NOT NULL,
    duration      INT           NOT NULL,
    items         VARCHAR(200)  NOT NULL,
    first_buys    VARCHAR(2000) NOT NULL DEFAULT '',
    PRIMARY KEY (match_id, hero_id)
);

CREATE INDEX IF NOT EXISTS idx_hero_time ON meta_build (hero_id, start_time);

CREATE TABLE IF NOT EXISTS item_cn (
    item_id  INT         NOT NULL,
    item_key VARCHAR(64) NOT NULL,
    name_cn  VARCHAR(64) NOT NULL,
    PRIMARY KEY (item_id)
);

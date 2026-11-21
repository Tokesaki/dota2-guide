-- ============================================================
-- Dota 2 新手入门教学网站 —— 比赛复盘模块建表脚本
--
-- 作用：建 4 张复盘模块要用的表。全部是 CREATE TABLE IF NOT EXISTS，
--       可以重复执行，不会动 hero / hero_skill 里的数据。
--
-- 执行时机：init.sql、heroes_full.sql、skills_full.sql 之后执行一次即可。
--
--   od_cache    —— OpenDota 接口返回的 JSON 原样缓存（免费额度每天约 2000 次，必须缓存）
--   meta_scan   —— 已经扫描过的高分局比赛 id，避免重复拉取
--   meta_build  —— 高分局每场每个英雄的出装（定时任务每小时攒一批）
--   item_cn     —— 物品中文名（可选，由 sql/download_items_cn.js 生成 items_cn.sql 填充；
--                  没有这张表的数据时页面显示英文名）
-- ============================================================
SET NAMES utf8mb4;

USE dota_guide;

CREATE TABLE IF NOT EXISTS od_cache (
    cache_key  VARCHAR(100) NOT NULL COMMENT '缓存键，如 match:9098052821、recent:869293166',
    body       MEDIUMTEXT   NOT NULL COMMENT 'OpenDota 返回的 JSON 原文',
    fetched_at DATETIME     NOT NULL COMMENT '拉取时间（判断是否过期）',
    PRIMARY KEY (cache_key)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'OpenDota 接口缓存';

CREATE TABLE IF NOT EXISTS meta_scan (
    match_id   BIGINT   NOT NULL COMMENT '比赛 id',
    scanned_at DATETIME NOT NULL COMMENT '扫描时间',
    PRIMARY KEY (match_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '已扫描的高分局比赛';

CREATE TABLE IF NOT EXISTS meta_build (
    match_id      BIGINT        NOT NULL COMMENT '比赛 id',
    hero_id       INT           NOT NULL COMMENT '英雄 id（= hero.id = 游戏内 hero_id）',
    win           TINYINT(1)    NOT NULL COMMENT '这个英雄所在方是否获胜',
    avg_rank_tier INT           NOT NULL DEFAULT 0 COMMENT '这场比赛的平均段位（OpenDota rank_tier，80 = 冠绝）',
    start_time    BIGINT        NOT NULL COMMENT '开始时间（Unix 秒）',
    duration      INT           NOT NULL COMMENT '比赛时长（秒）',
    items         VARCHAR(200)  NOT NULL COMMENT '最终 6 格 + 背包物品 id，逗号分隔',
    first_buys    VARCHAR(2000) NOT NULL DEFAULT '' COMMENT '已解析比赛才有：物品key:首次购买秒数，逗号分隔',
    PRIMARY KEY (match_id, hero_id),
    KEY idx_hero_time (hero_id, start_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '高分局出装样本';

CREATE TABLE IF NOT EXISTS item_cn (
    item_id  INT         NOT NULL COMMENT '物品 id（游戏内 id，和 OpenDota 一致）',
    item_key VARCHAR(64) NOT NULL COMMENT '物品内部名，如 blink',
    name_cn  VARCHAR(64) NOT NULL COMMENT '中文名，如 闪烁匕首',
    PRIMARY KEY (item_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '物品中文名（可选）';

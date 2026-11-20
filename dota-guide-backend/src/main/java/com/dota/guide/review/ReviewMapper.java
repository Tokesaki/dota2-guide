package com.dota.guide.review;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

/**
 * ReviewMapper —— 复盘模块的全部数据库操作，SQL 在 resources/mapper/ReviewMapper.xml
 *
 * 表结构见 sql/review.sql。
 */
@Mapper
public interface ReviewMapper {

    /** od_cache 的一行 */
    class CacheRow {
        public String body;
        public LocalDateTime fetchedAt;
    }

    /** item_cn 的一行 */
    class ItemCn {
        public Integer itemId;
        public String nameCn;
    }

    /** meta_build 的一行（写入用） */
    class BuildInsert {
        public long matchId;
        public int heroId;
        public boolean win;
        public int avgRankTier;
        public long startTime;
        public int duration;
        public String items;
        public String firstBuys;
    }

    // ---------- OpenDota 缓存 ----------

    CacheRow findCache(@Param("key") String key);

    void saveCache(@Param("key") String key, @Param("body") String body);

    // ---------- 物品中文名 ----------

    List<ItemCn> findItemCn();

    // ---------- 高分局出装 ----------

    int countScanned(@Param("matchId") long matchId);

    void markScanned(@Param("matchId") long matchId);

    void insertBuild(BuildInsert row);

    /** 某英雄在 since（Unix 秒）之后的出装样本，最新的在前，最多 limit 条 */
    List<BuildStats.BuildRow> findBuilds(@Param("heroId") int heroId, @Param("since") long since, @Param("limit") int limit);

    /** 删掉 before（Unix 秒）之前的旧样本，返回删除条数 */
    int deleteBuildsBefore(@Param("before") long before);
}

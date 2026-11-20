package com.dota.guide.review;

import java.util.List;

/**
 * 复盘模块对前端返回的数据结构，全部用 record（Java 17）：字段一行写完，Jackson 直接序列化成 JSON。
 */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    /** 英雄的图鉴信息（来自本站 hero 表） */
    public record HeroBrief(long id, String name, String slug, String avatar, String attr,
                            String pos, String tips, String items, String counters) {
    }

    /** 最近比赛列表的一行 */
    public record RecentMatch(long matchId, long startTime, int duration, int gameMode, int lobbyType,
                              HeroBrief hero, boolean win, int kills, int deaths, int assists,
                              int gpm, int xpm, int lastHits, boolean parsed, Integer averageRank) {
    }

    public record PlayerProfile(long accountId, String name, String avatar, Integer rankTier,
                                List<RecentMatch> matches) {
    }

    /** 一件物品：id、名字、图标，以及（解析过的比赛才有的）首次购买时间 */
    public record ItemView(int id, String key, String name, String img, int cost, Integer time) {
    }

    /** 一项百分位指标，如 GPM 超过 64% 的同英雄玩家 */
    public record Benchmark(String key, String label, double raw, double pct) {
    }

    /** 你的出装和高分局的对比 */
    public record BuildCompare(int sampleSize, List<BuildStats.ItemStat> common,
                               List<ItemView> missing, List<ItemView> unusual) {
    }

    public record PlayerReview(
            int index, Long accountId, String personaname, boolean radiant, boolean win,
            HeroBrief hero,
            int kills, int deaths, int assists, int gpm, int xpm, int lastHits, int denies,
            int netWorth, int heroDamage, int towerDamage, int level,
            Integer laneRole, int guessedPos, boolean posFromLane, Boolean posMatchesGuide,
            Integer lastHits10, Integer denies10,
            List<Benchmark> benchmarks,
            List<ItemView> items,
            BuildCompare buildCompare,
            List<ReviewRules.Advice> advice) {
    }

    public record MatchReview(long matchId, long startTime, int duration, boolean radiantWin,
                              int gameMode, int radiantScore, int direScore,
                              boolean parsed, boolean parseRequested,
                              List<PlayerReview> players) {
    }

    /** 英雄页"高分局出装"：source = high-rank（本站攒的高分局样本）或 popularity（OpenDota 全分段热度） */
    public record HeroBuilds(String source, int sampleSize, int minRank,
                             List<BuildStats.ItemStat> core,
                             List<PhaseItems> phases) {
    }

    /** OpenDota itemPopularity 的一个阶段（开局 / 前期 / 中期 / 后期） */
    public record PhaseItems(String phase, String label, List<PhaseItem> items) {
    }

    public record PhaseItem(int id, String key, String name, String img, int cost, int count) {
    }
}

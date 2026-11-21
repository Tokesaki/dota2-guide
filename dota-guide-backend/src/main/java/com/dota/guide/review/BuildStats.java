package com.dota.guide.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * BuildStats —— 把一堆高分局出装样本汇总成"这个英雄常出什么、多久出"
 *
 * 输入：meta_build 表里同一个英雄的若干行（一行 = 一场比赛里这个英雄的出装）
 * 输出：按出场率从高到低的物品列表，每件带出场率、胜率、平均首次购买时间
 *
 * 纯函数，不碰数据库，单元测试见 BuildStatsTest。
 */
public final class BuildStats {

    private BuildStats() {
    }

    /** 一场比赛里一个英雄的出装（对应 meta_build 表的一行） */
    public static class BuildRow {
        public boolean win;
        /** 最终物品 id，逗号分隔，如 "1,48,116" */
        public String items;
        /** 首次购买时间，"key:秒" 逗号分隔，如 "blink:840,bfury:1020"；没解析过的比赛是空串 */
        public String firstBuys;

        public BuildRow() {
        }

        public BuildRow(boolean win, String items, String firstBuys) {
            this.win = win;
            this.items = items;
            this.firstBuys = firstBuys;
        }
    }

    /**
     * @param pickRate 出场率：样本里有多少比例的比赛最后带着这件
     * @param winRate  带这件的比赛的胜率
     * @param avgTime  平均首次购买时间（秒）；样本里没有购买记录时为 null
     */
    public record ItemStat(int itemId, String key, String name, String img, int cost,
                           double pickRate, double winRate, Integer avgTime) {
    }

    /**
     * @param minCost 只统计价格不低于这个数的物品（过滤掉树枝、药膏这类消耗品和小件）
     * @param limit   最多返回几件
     */
    public static List<ItemStat> aggregate(List<BuildRow> rows, ItemCatalog catalog, int minCost, int limit) {
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        Map<Integer, int[]> counts = new HashMap<>();   // id → [出现次数, 赢的次数]
        Map<String, long[]> times = new HashMap<>();    // key → [时间总和, 样本数]

        for (BuildRow row : rows) {
            Set<Integer> seen = new HashSet<>();        // 一场里出两件同样的只算一次
            for (int id : parseIds(row.items)) {
                if (id > 0 && seen.add(id)) {
                    int[] c = counts.computeIfAbsent(id, k -> new int[2]);
                    c[0]++;
                    if (row.win) {
                        c[1]++;
                    }
                }
            }
            parseTimes(row.firstBuys).forEach((key, sec) -> {
                long[] t = times.computeIfAbsent(key, k -> new long[2]);
                t[0] += sec;
                t[1]++;
            });
        }

        int n = rows.size();
        List<ItemStat> out = new ArrayList<>();
        counts.forEach((id, c) -> {
            ItemCatalog.Item item = catalog.byId(id);
            if (item == null || item.cost() < minCost) {
                return;
            }
            long[] t = times.get(item.key());
            Integer avg = (t == null || t[1] == 0) ? null : (int) (t[0] / t[1]);
            out.add(new ItemStat(id, item.key(), item.name(), item.img(), item.cost(),
                    (double) c[0] / n, (double) c[1] / c[0], avg));
        });
        out.sort(Comparator.comparingDouble(ItemStat::pickRate).reversed()
                .thenComparing(ItemStat::itemId));
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /** "1,48,,116" → [1, 48, 116]，非法片段跳过 */
    public static List<Integer> parseIds(String csv) {
        List<Integer> ids = new ArrayList<>();
        if (csv == null || csv.isBlank()) {
            return ids;
        }
        for (String s : csv.split(",")) {
            try {
                ids.add(Integer.parseInt(s.trim()));
            } catch (NumberFormatException ignored) {
                // 空片段或脏数据，跳过
            }
        }
        return ids;
    }

    /** "blink:840,bfury:1020" → {blink=840, bfury=1020}，非法片段跳过 */
    public static Map<String, Integer> parseTimes(String csv) {
        Map<String, Integer> m = new HashMap<>();
        if (csv == null || csv.isBlank()) {
            return m;
        }
        for (String s : csv.split(",")) {
            int i = s.lastIndexOf(':');
            if (i <= 0) {
                continue;
            }
            try {
                m.put(s.substring(0, i).trim(), Integer.parseInt(s.substring(i + 1).trim()));
            } catch (NumberFormatException ignored) {
                // 跳过
            }
        }
        return m;
    }
}

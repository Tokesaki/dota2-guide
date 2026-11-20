package com.dota.guide.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 复盘模块纯逻辑的单元测试：不需要数据库、不需要网络，./mvnw test -Dtest=ReviewLogicTest 单独跑。
 */
class ReviewLogicTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ItemCatalog catalog() throws Exception {
        JsonNode constants = JSON.readTree("""
                {
                  "blink":  {"id": 1,   "dname": "Blink Dagger",  "cost": 2250, "img": "/apps/dota2/images/items/blink.png"},
                  "bfury":  {"id": 145, "dname": "Battle Fury",   "cost": 4100, "img": "/apps/dota2/images/items/bfury.png"},
                  "tango":  {"id": 44,  "dname": "Tango",         "cost": 90,   "img": "/apps/dota2/images/items/tango.png"},
                  "black_king_bar": {"id": 116, "dname": "Black King Bar", "cost": 4050, "img": ""},
                  "recipe_bfury": {"id": 144, "dname": "Recipe", "cost": 0, "img": ""}
                }""");
        return ItemCatalog.fromOpenDota(constants, Map.of(1, "闪烁匕首"));
    }

    // ---------- ItemCatalog ----------

    @Test
    void catalogPrefersChineseNameAndSkipsRecipes() throws Exception {
        ItemCatalog c = catalog();
        assertEquals("闪烁匕首", c.byId(1).name());
        assertEquals("Battle Fury", c.byKey("bfury").name());
        assertTrue(c.byId(1).img().startsWith("https://cdn.cloudflare.steamstatic.com/apps/"));
        assertNull(c.byId(144));
        assertNull(c.byId(0));
    }

    // ---------- BuildStats ----------

    @Test
    void aggregateCountsPickRateWinRateAndAvgTime() throws Exception {
        List<BuildStats.BuildRow> rows = List.of(
                new BuildStats.BuildRow(true, "1,145,44", "blink:600,bfury:900"),
                new BuildStats.BuildRow(false, "145,116", "bfury:1100"),
                new BuildStats.BuildRow(true, "145,145", ""),
                new BuildStats.BuildRow(true, "116", ""));
        List<BuildStats.ItemStat> out = BuildStats.aggregate(rows, catalog(), 1000, 10);

        assertEquals(3, out.size(), "tango 价格低于 1000 被过滤");
        BuildStats.ItemStat bfury = out.get(0);
        assertEquals("bfury", bfury.key());
        assertEquals(0.75, bfury.pickRate(), 1e-9, "同一场两把狂战斧只算一次");
        assertEquals(2.0 / 3, bfury.winRate(), 1e-9);
        assertEquals(1000, bfury.avgTime());
        assertEquals(0.5, out.get(1).pickRate(), 1e-9);
        assertEquals(116, out.get(1).itemId(), "出场率相同时按 id 排，结果稳定");
        assertNull(out.get(1).avgTime(), "没有购买记录就是 null");
    }

    @Test
    void aggregateHandlesEmptyAndDirtyInput() throws Exception {
        assertTrue(BuildStats.aggregate(List.of(), catalog(), 0, 10).isEmpty());
        assertEquals(List.of(1, 2), BuildStats.parseIds("1,,x,2"));
        assertEquals(Map.of("blink", 600), BuildStats.parseTimes("blink:600,bad,x:y"));
    }

    // ---------- PositionGuess ----------

    @Test
    void guessByLaneRole() {
        List<PositionGuess.Slot> slots = List.of(
                new PositionGuess.Slot(0, true, 20000, 1),   // 优势路高经济 → 1
                new PositionGuess.Slot(1, true, 18000, 2),   // 中路 → 2
                new PositionGuess.Slot(2, true, 15000, 3),   // 劣势路高经济 → 3
                new PositionGuess.Slot(3, true, 8000, 3),    // 劣势路低经济 → 4
                new PositionGuess.Slot(4, true, 6000, 1),    // 优势路低经济 → 5
                new PositionGuess.Slot(5, false, 1, 4),      // 夜魇有人打野 → 按经济补空位
                new PositionGuess.Slot(6, false, 9, 2),
                new PositionGuess.Slot(7, false, 8, 1),
                new PositionGuess.Slot(8, false, 7, 3),
                new PositionGuess.Slot(9, false, 2, 3));
        Map<Integer, Integer> pos = PositionGuess.guess(slots);
        assertEquals(1, pos.get(0));
        assertEquals(2, pos.get(1));
        assertEquals(3, pos.get(2));
        assertEquals(4, pos.get(3));
        assertEquals(5, pos.get(4));
        assertEquals(2, pos.get(6));
        assertEquals(1, pos.get(7));
        assertEquals(3, pos.get(8));
        assertEquals(4, pos.get(9));
        assertEquals(5, pos.get(5), "打野的补到剩下的 5 号位");
    }

    @Test
    void guessByNetWorthWhenUnparsed() {
        List<PositionGuess.Slot> slots = List.of(
                new PositionGuess.Slot(0, true, 100, null),
                new PositionGuess.Slot(1, true, 500, null),
                new PositionGuess.Slot(2, true, 300, null));
        Map<Integer, Integer> pos = PositionGuess.guess(slots);
        assertEquals(1, pos.get(1));
        assertEquals(2, pos.get(2));
        assertEquals(3, pos.get(0));
    }

    @Test
    void parseRecommendedPositions() {
        assertEquals(Set.of(4, 5), PositionGuess.parseRecommended("4/5号位"));
        assertEquals(Set.of(1), PositionGuess.parseRecommended("1号位"));
        assertEquals(Set.of(1, 4), PositionGuess.parseRecommended("4/1号位"));
        assertTrue(PositionGuess.parseRecommended(null).isEmpty());
    }

    // ---------- ReviewRules ----------

    private static ReviewRules.Facts facts(int pos, Set<Integer> rec, int deaths, Integer lh10,
                                           Map<String, Double> pct, List<String> missing,
                                           List<ReviewRules.LateItem> late) {
        return new ReviewRules.Facts(pos, true, rec, "敌法师", 40 * 60, deaths, lh10, pct, missing, late);
    }

    private static boolean has(List<ReviewRules.Advice> list, String level, String fragment) {
        return list.stream().anyMatch(a -> a.level().equals(level) && a.text().contains(fragment));
    }

    @Test
    void coreWithPoorFarmGetsFarmAdvice() {
        List<ReviewRules.Advice> out = ReviewRules.advise(facts(1, Set.of(1), 4, 30,
                Map.of("gold_per_min", 0.2, "last_hits_per_min", 0.1), List.of(), List.of()));
        assertTrue(has(out, "bad", "发育偏慢"));
        assertTrue(has(out, "warn", "补刀速度"));
        assertTrue(has(out, "warn", "10 分钟正补 30 个"));
        assertFalse(has(out, "info", "号位"), "号位和推荐一致时不提示");
    }

    @Test
    void supportIsNotJudgedOnFarm() {
        List<ReviewRules.Advice> out = ReviewRules.advise(facts(5, Set.of(4, 5), 4, 5,
                Map.of("gold_per_min", 0.1, "assists_per_min", 0.2), List.of(), List.of()));
        assertFalse(out.stream().anyMatch(a -> a.text().contains("发育偏慢")));
        assertFalse(out.stream().anyMatch(a -> a.text().contains("正补")));
        assertTrue(has(out, "warn", "助攻"));
    }

    @Test
    void positionMismatchDeathsAndItems() {
        List<ReviewRules.Advice> out = ReviewRules.advise(facts(3, Set.of(1), 13, null,
                Map.of("gold_per_min", 0.9), List.of("狂战斧"),
                List.of(new ReviewRules.LateItem("闪烁匕首", 1500, 900))));
        assertTrue(has(out, "info", "3 号位"));
        assertTrue(has(out, "info", "打 1 号位"));
        assertTrue(has(out, "bad", "死了 13 次"));
        assertTrue(has(out, "good", "发育很好"));
        assertTrue(has(out, "warn", "狂战斧"));
        assertTrue(has(out, "warn", "闪烁匕首 在 25:00 才出，高分局平均 15:00，慢了 10 分钟"));
    }

    @Test
    void fewDeathsInLongGameIsPraised() {
        List<ReviewRules.Advice> out = ReviewRules.advise(facts(1, Set.of(1), 1, null, Map.of(), List.of(), List.of()));
        assertTrue(has(out, "good", "只死了 1 次"));
    }

    @Test
    void clockFormatsNegativeTimes() {
        assertEquals("-0:30", ReviewRules.clock(-30));
        assertEquals("12:05", ReviewRules.clock(725));
    }
}

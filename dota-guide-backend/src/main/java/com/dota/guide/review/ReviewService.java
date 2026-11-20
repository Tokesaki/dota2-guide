package com.dota.guide.review;

import com.dota.guide.entity.Hero;
import com.dota.guide.entity.HeroDetailVO;
import com.dota.guide.mapper.HeroMapper;
import com.dota.guide.review.ReviewDtos.Benchmark;
import com.dota.guide.review.ReviewDtos.BuildCompare;
import com.dota.guide.review.ReviewDtos.HeroBrief;
import com.dota.guide.review.ReviewDtos.HeroBuilds;
import com.dota.guide.review.ReviewDtos.ItemView;
import com.dota.guide.review.ReviewDtos.MatchReview;
import com.dota.guide.review.ReviewDtos.PhaseItem;
import com.dota.guide.review.ReviewDtos.PhaseItems;
import com.dota.guide.review.ReviewDtos.PlayerProfile;
import com.dota.guide.review.ReviewDtos.PlayerReview;
import com.dota.guide.review.ReviewDtos.RecentMatch;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReviewService —— 把 OpenDota 的比赛数据和本站图鉴拼在一起，产出复盘结果
 *
 * 一场比赛的复盘 = 对 10 个玩家各做一遍：
 *   1. 英雄翻成中文，带上图鉴里的推荐号位、打法建议、克制关系；
 *   2. 推测这局打的号位（PositionGuess）；
 *   3. 取 OpenDota 自带的百分位（GPM、补刀……超过全服多少同英雄玩家）；
 *   4. 最终出装 + 首次购买时间，和高分局样本对比（BuildStats）；
 *   5. 把以上事实交给 ReviewRules 生成建议。
 */
@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    /** 高分局样本少于这个数就不做出装对比（样本太少，统计没意义） */
    static final int MIN_SAMPLES = 20;
    /** 只看最近多少天的样本（跟上版本变化） */
    static final int SAMPLE_DAYS = 30;
    /** 只对比价格不低于这个数的"大件" */
    static final int BIG_ITEM_COST = 2000;

    /** 百分位指标：OpenDota benchmarks 里的 key → 中文名，按页面显示顺序排 */
    static final Map<String, String> BENCHMARK_LABELS = new LinkedHashMap<>();

    static {
        BENCHMARK_LABELS.put("gold_per_min", "每分钟经济");
        BENCHMARK_LABELS.put("xp_per_min", "每分钟经验");
        BENCHMARK_LABELS.put("last_hits_per_min", "每分钟补刀");
        BENCHMARK_LABELS.put("hero_damage_per_min", "每分钟英雄伤害");
        BENCHMARK_LABELS.put("tower_damage", "推塔伤害");
        BENCHMARK_LABELS.put("kills_per_min", "每分钟击杀");
        BENCHMARK_LABELS.put("assists_per_min", "每分钟助攻");
    }

    private final OpenDotaService od;
    private final HeroMapper heroMapper;
    private final ReviewMapper reviewMapper;
    private final int metaMinRank;

    public ReviewService(OpenDotaService od, HeroMapper heroMapper, ReviewMapper reviewMapper,
                         @Value("${review.meta.min-rank:80}") int metaMinRank) {
        this.od = od;
        this.heroMapper = heroMapper;
        this.reviewMapper = reviewMapper;
        this.metaMinRank = metaMinRank;
    }

    // ==================== 玩家最近比赛 ====================

    public PlayerProfile profile(long accountId) {
        JsonNode p = od.player(accountId);
        JsonNode profile = p.path("profile");
        if (profile.isMissingNode() || profile.isNull()) {
            throw new ReviewException(404, "OpenDota 上没有这个玩家，或者他的比赛数据没有公开（游戏里设置 → 公开比赛数据）");
        }
        JsonNode list = od.recentMatches(accountId);
        Set<Long> heroIds = new LinkedHashSet<>();
        list.forEach(m -> heroIds.add(m.path("hero_id").asLong()));
        Map<Long, Hero> heroes = loadHeroes(heroIds);

        List<RecentMatch> matches = new ArrayList<>();
        for (JsonNode m : list) {
            boolean radiant = m.path("player_slot").asInt() < 128;
            matches.add(new RecentMatch(
                    m.path("match_id").asLong(), m.path("start_time").asLong(), m.path("duration").asInt(),
                    m.path("game_mode").asInt(), m.path("lobby_type").asInt(),
                    brief(heroes.get(m.path("hero_id").asLong()), m.path("hero_id").asLong()),
                    radiant == m.path("radiant_win").asBoolean(),
                    m.path("kills").asInt(), m.path("deaths").asInt(), m.path("assists").asInt(),
                    m.path("gold_per_min").asInt(), m.path("xp_per_min").asInt(), m.path("last_hits").asInt(),
                    m.hasNonNull("version"),
                    m.hasNonNull("average_rank") ? m.get("average_rank").asInt() : null));
        }
        return new PlayerProfile(accountId, profile.path("personaname").asText(""),
                profile.path("avatarfull").asText(""),
                p.hasNonNull("rank_tier") ? p.get("rank_tier").asInt() : null, matches);
    }

    // ==================== 单场复盘 ====================

    public MatchReview review(long matchId) {
        JsonNode m = od.match(matchId);
        boolean parsed = OpenDotaService.isParsed(m);
        boolean parseRequested = false;
        if (!parsed) {
            // 没解析过就顺手请求一次；额度用完之类的失败不影响复盘的其它部分
            try {
                parseRequested = od.requestParse(matchId) || od.parseRequestedRecently(matchId);
            } catch (ReviewException e) {
                log.warn("请求解析比赛 {} 失败：{}", matchId, e.getMessage());
            }
        }

        ItemCatalog catalog = safeCatalog();
        JsonNode players = m.path("players");
        int duration = m.path("duration").asInt();

        Set<Long> heroIds = new LinkedHashSet<>();
        List<PositionGuess.Slot> slots = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            JsonNode p = players.get(i);
            heroIds.add(p.path("hero_id").asLong());
            slots.add(new PositionGuess.Slot(i, p.path("isRadiant").asBoolean(p.path("player_slot").asInt() < 128),
                    p.path("net_worth").asInt(p.path("total_gold").asInt()),
                    p.hasNonNull("lane_role") ? p.get("lane_role").asInt() : null));
        }
        Map<Long, Hero> heroes = loadHeroes(heroIds);
        Map<Integer, Integer> pos = PositionGuess.guess(slots);
        boolean posFromLane = slots.stream().allMatch(s -> s.laneRole() != null);

        List<PlayerReview> out = new ArrayList<>();
        for (int i = 0; i < players.size(); i++) {
            out.add(reviewPlayer(i, players.get(i), heroes, pos.getOrDefault(i, 5), posFromLane, duration, catalog));
        }
        return new MatchReview(matchId, m.path("start_time").asLong(), duration, m.path("radiant_win").asBoolean(),
                m.path("game_mode").asInt(), m.path("radiant_score").asInt(), m.path("dire_score").asInt(),
                parsed, parseRequested, out);
    }

    /** 手动请求解析（前端"重新请求解析"按钮） */
    public boolean requestParse(long matchId) {
        return od.requestParse(matchId);
    }

    private PlayerReview reviewPlayer(int index, JsonNode p, Map<Long, Hero> heroes, int guessedPos,
                                      boolean posFromLane, int duration, ItemCatalog catalog) {
        long heroId = p.path("hero_id").asLong();
        Hero hero = heroes.get(heroId);
        HeroBrief brief = brief(hero, heroId);
        Set<Integer> recommended = PositionGuess.parseRecommended(hero == null ? null : hero.getPos());

        // ---------- 百分位 ----------
        List<Benchmark> benchmarks = new ArrayList<>();
        Map<String, Double> pct = new HashMap<>();
        JsonNode bm = p.path("benchmarks");
        BENCHMARK_LABELS.forEach((key, label) -> {
            JsonNode b = bm.path(key);
            if (b.hasNonNull("pct")) {
                benchmarks.add(new Benchmark(key, label, b.path("raw").asDouble(), b.get("pct").asDouble()));
                pct.put(key, b.get("pct").asDouble());
            }
        });

        // ---------- 出装 ----------
        Map<String, Integer> firstBuy = new HashMap<>();
        Iterator<Map.Entry<String, JsonNode>> fb = p.path("first_purchase_time").fields();
        while (fb.hasNext()) {
            Map.Entry<String, JsonNode> e = fb.next();
            if (e.getValue().isNumber()) {
                firstBuy.put(e.getKey(), e.getValue().asInt());
            }
        }
        List<Integer> ownedIds = finalItemIds(p);
        List<ItemView> items = new ArrayList<>();
        for (String slot : new String[]{"item_0", "item_1", "item_2", "item_3", "item_4", "item_5",
                "backpack_0", "backpack_1", "backpack_2", "item_neutral"}) {
            int id = p.path(slot).asInt(0);
            ItemCatalog.Item it = catalog.byId(id);
            if (it != null) {
                items.add(new ItemView(it.id(), it.key(), it.name(), it.img(), it.cost(), firstBuy.get(it.key())));
            }
        }

        // ---------- 和高分局对比 ----------
        BuildCompare compare = null;
        List<String> missingNames = new ArrayList<>();
        List<ReviewRules.LateItem> late = new ArrayList<>();
        List<BuildStats.BuildRow> rows = reviewMapper.findBuilds((int) heroId,
                System.currentTimeMillis() / 1000 - SAMPLE_DAYS * 86400L, 1000);
        if (rows.size() >= MIN_SAMPLES && !catalog.isEmpty()) {
            List<BuildStats.ItemStat> all = BuildStats.aggregate(rows, catalog, 0, Integer.MAX_VALUE);
            List<BuildStats.ItemStat> common = BuildStats.aggregate(rows, catalog, 1500, 12);
            Map<Integer, BuildStats.ItemStat> statById = new HashMap<>();
            all.forEach(s -> statById.put(s.itemId(), s));

            List<ItemView> missing = new ArrayList<>();
            if (duration >= 25 * 60) {
                for (BuildStats.ItemStat s : common) {
                    if (s.pickRate() >= 0.5 && s.cost() >= BIG_ITEM_COST && !ownedIds.contains(s.itemId())) {
                        missing.add(new ItemView(s.itemId(), s.key(), s.name(), s.img(), s.cost(), s.avgTime()));
                        missingNames.add(s.name());
                    }
                }
            }
            List<ItemView> unusual = new ArrayList<>();
            for (ItemView it : items) {
                if (it.cost() < BIG_ITEM_COST) {
                    continue;
                }
                BuildStats.ItemStat s = statById.get(it.id());
                if (s == null || s.pickRate() < 0.05) {
                    unusual.add(it);
                }
                if (s != null && s.avgTime() != null && it.time() != null
                        && it.time() - s.avgTime() >= ReviewRules.LATE_SECONDS) {
                    late.add(new ReviewRules.LateItem(it.name(), it.time(), s.avgTime()));
                }
            }
            compare = new BuildCompare(rows.size(), common, missing, unusual);
        }

        // ---------- 对线 ----------
        Integer lh10 = at(p.path("lh_t"), 10);
        Integer dn10 = at(p.path("dn_t"), 10);

        List<ReviewRules.Advice> advice = ReviewRules.advise(new ReviewRules.Facts(
                guessedPos, posFromLane, recommended, brief.name(), duration,
                p.path("deaths").asInt(), lh10, pct, missingNames, late));

        boolean radiant = p.path("isRadiant").asBoolean(p.path("player_slot").asInt() < 128);
        return new PlayerReview(
                index, p.hasNonNull("account_id") ? p.get("account_id").asLong() : null,
                p.path("personaname").asText(""), radiant, p.path("win").asInt(0) == 1,
                brief,
                p.path("kills").asInt(), p.path("deaths").asInt(), p.path("assists").asInt(),
                p.path("gold_per_min").asInt(), p.path("xp_per_min").asInt(),
                p.path("last_hits").asInt(), p.path("denies").asInt(),
                p.path("net_worth").asInt(), p.path("hero_damage").asInt(), p.path("tower_damage").asInt(),
                p.path("level").asInt(),
                p.hasNonNull("lane_role") ? p.get("lane_role").asInt() : null,
                guessedPos, posFromLane, recommended.isEmpty() ? null : recommended.contains(guessedPos),
                lh10, dn10, benchmarks, items, compare, advice);
    }

    // ==================== 英雄页：高分局出装 ====================

    public HeroBuilds heroBuilds(String key) {
        Long numeric = key.matches("\\d{1,9}") ? Long.valueOf(key) : null;
        HeroDetailVO hero = heroMapper.findByKey(key, numeric);
        if (hero == null) {
            throw new ReviewException(404, "英雄不存在（" + key + "）");
        }
        int heroId = hero.getId().intValue();
        ItemCatalog catalog = safeCatalog();

        List<BuildStats.BuildRow> rows = reviewMapper.findBuilds(heroId,
                System.currentTimeMillis() / 1000 - SAMPLE_DAYS * 86400L, 1000);
        List<BuildStats.ItemStat> core = rows.size() >= MIN_SAMPLES
                ? BuildStats.aggregate(rows, catalog, 1500, 12) : List.of();

        List<PhaseItems> phases = new ArrayList<>();
        try {
            JsonNode pop = od.itemPopularity(heroId);
            String[][] defs = {{"start_game_items", "开局"}, {"early_game_items", "前期"},
                    {"mid_game_items", "中期"}, {"late_game_items", "后期"}};
            for (String[] d : defs) {
                List<PhaseItem> list = new ArrayList<>();
                Iterator<Map.Entry<String, JsonNode>> it = pop.path(d[0]).fields();
                while (it.hasNext()) {
                    Map.Entry<String, JsonNode> e = it.next();
                    ItemCatalog.Item item = catalog.byId(Integer.parseInt(e.getKey()));
                    if (item != null) {
                        list.add(new PhaseItem(item.id(), item.key(), item.name(), item.img(), item.cost(), e.getValue().asInt()));
                    }
                }
                list.sort((a, b) -> Integer.compare(b.count(), a.count()));
                phases.add(new PhaseItems(d[0], d[1], list.size() > 8 ? list.subList(0, 8) : list));
            }
        } catch (ReviewException | NumberFormatException e) {
            log.warn("拉取英雄 {} 出装热度失败：{}", heroId, e.getMessage());
        }
        return new HeroBuilds(core.isEmpty() ? "popularity" : "high-rank", rows.size(), metaMinRank, core, phases);
    }

    // ==================== 工具 ====================

    private ItemCatalog safeCatalog() {
        try {
            return od.catalog();
        } catch (ReviewException e) {
            log.warn("物品字典加载失败：{}", e.getMessage());
            return ItemCatalog.EMPTY;
        }
    }

    private Map<Long, Hero> loadHeroes(Set<Long> ids) {
        Map<Long, Hero> map = new HashMap<>();
        if (!ids.isEmpty()) {
            for (Hero h : heroMapper.findByIds(ids)) {
                map.put(h.getId(), h);
            }
        }
        return map;
    }

    /** 本站图鉴里没有的英雄（新英雄还没补数据）也能显示，只是没有中文名和建议 */
    static HeroBrief brief(Hero h, long heroId) {
        if (h == null) {
            return new HeroBrief(heroId, "英雄 #" + heroId, null, null, null, null, null, null, null);
        }
        return new HeroBrief(h.getId(), h.getName(), h.getSlug(), h.getAvatar(), h.getAttr(),
                h.getPos(), h.getTips(), h.getItems(), h.getCounters());
    }

    static List<Integer> finalItemIds(JsonNode p) {
        List<Integer> ids = new ArrayList<>();
        for (String slot : new String[]{"item_0", "item_1", "item_2", "item_3", "item_4", "item_5",
                "backpack_0", "backpack_1", "backpack_2"}) {
            int id = p.path(slot).asInt(0);
            if (id > 0) {
                ids.add(id);
            }
        }
        return ids;
    }

    private static Integer at(JsonNode arr, int i) {
        return arr.isArray() && arr.size() > i ? arr.get(i).asInt() : null;
    }
}

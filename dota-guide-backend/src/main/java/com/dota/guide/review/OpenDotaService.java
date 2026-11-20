package com.dota.guide.review;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * OpenDotaService —— 带缓存的 OpenDota 数据访问
 *
 * OpenDota 免费额度大约每分钟 60 次、每天 2000 次，所以除了"拉高分局样本"之外，
 * 所有请求都先查 od_cache 表，没过期就直接用缓存。各类数据的过期时间：
 *
 *   比赛详情（已解析）  永不过期 —— 比赛打完数据就不会再变
 *   比赛详情（未解析）  10 分钟  —— 请求解析后过一会儿再拉，就能拿到详细时间线
 *   最近比赛列表        5 分钟
 *   物品常量            7 天
 *   英雄出装热度        1 天
 */
@Service
public class OpenDotaService {

    private static final Duration UNPARSED_TTL = Duration.ofMinutes(10);
    private static final Duration RECENT_TTL = Duration.ofMinutes(5);
    private static final Duration CONSTANTS_TTL = Duration.ofDays(7);
    private static final Duration POPULARITY_TTL = Duration.ofDays(1);
    private static final Duration PARSE_REQUEST_COOLDOWN = Duration.ofMinutes(30);

    private final OpenDotaClient client;
    private final ReviewMapper mapper;
    private final ObjectMapper json;

    /** 物品字典放内存里，一天重建一次 */
    private volatile ItemCatalog catalog;
    private volatile LocalDateTime catalogBuiltAt;

    public OpenDotaService(OpenDotaClient client, ReviewMapper mapper, ObjectMapper json) {
        this.client = client;
        this.mapper = mapper;
        this.json = json;
    }

    /** 比赛详情 */
    public JsonNode match(long matchId) {
        String key = "match:" + matchId;
        ReviewMapper.CacheRow row = mapper.findCache(key);
        if (row != null) {
            JsonNode cached = parse(row.body);
            if (isParsed(cached) || fresh(row, UNPARSED_TTL)) {
                return cached;
            }
        }
        String body = client.get("/matches/" + matchId);
        JsonNode node = parse(body);
        if (!node.has("players")) {
            throw new ReviewException(404, "找不到比赛 " + matchId);
        }
        mapper.saveCache(key, body);
        return node;
    }

    /** 不走缓存的比赛详情（高分局样本只用一次，没必要占缓存表） */
    public JsonNode matchUncached(long matchId) {
        return parse(client.get("/matches/" + matchId));
    }

    /** 玩家最近 20 场 */
    public JsonNode recentMatches(long accountId) {
        return cached("recent:" + accountId, RECENT_TTL, "/players/" + accountId + "/recentMatches");
    }

    /** 玩家资料（昵称、头像、段位） */
    public JsonNode player(long accountId) {
        return cached("player:" + accountId, Duration.ofHours(6), "/players/" + accountId);
    }

    /** 英雄各阶段出装热度（全分段，不分高低分） */
    public JsonNode itemPopularity(int heroId) {
        return cached("itempop:" + heroId, POPULARITY_TTL, "/heroes/" + heroId + "/itemPopularity");
    }

    /** 高分局公开比赛列表（不缓存，每次都要新的） */
    public JsonNode publicMatches(int minRank) {
        return parse(client.get("/publicMatches?min_rank=" + minRank));
    }

    /**
     * 请求 OpenDota 解析比赛录像。同一场 30 分钟内只发一次，避免浪费额度。
     *
     * @return true = 这次真的发出了请求；false = 最近已经请求过
     */
    public boolean requestParse(long matchId) {
        String key = "parsereq:" + matchId;
        ReviewMapper.CacheRow row = mapper.findCache(key);
        if (row != null && fresh(row, PARSE_REQUEST_COOLDOWN)) {
            return false;
        }
        String body = client.post("/request/" + matchId);
        mapper.saveCache(key, body.isEmpty() ? "{}" : body);
        return true;
    }

    /** 最近 30 分钟内是否请求过解析 */
    public boolean parseRequestedRecently(long matchId) {
        ReviewMapper.CacheRow row = mapper.findCache("parsereq:" + matchId);
        return row != null && fresh(row, PARSE_REQUEST_COOLDOWN);
    }

    public ItemCatalog catalog() {
        LocalDateTime built = catalogBuiltAt;
        if (catalog == null || built == null || built.isBefore(LocalDateTime.now().minusDays(1))) {
            synchronized (this) {
                if (catalog == null || catalogBuiltAt.isBefore(LocalDateTime.now().minusDays(1))) {
                    Map<Integer, String> cn = new HashMap<>();
                    for (ReviewMapper.ItemCn r : mapper.findItemCn()) {
                        cn.put(r.itemId, r.nameCn);
                    }
                    JsonNode constants = cached("constants:items", CONSTANTS_TTL, "/constants/items");
                    catalog = ItemCatalog.fromOpenDota(constants, cn);
                    catalogBuiltAt = LocalDateTime.now();
                }
            }
        }
        return catalog;
    }

    /** 比赛是否被 OpenDota 解析过（解析过才有出装时间、分路、补刀曲线） */
    public static boolean isParsed(JsonNode match) {
        return match != null && match.hasNonNull("version");
    }

    // ---------- 内部工具 ----------

    private JsonNode cached(String key, Duration ttl, String path) {
        ReviewMapper.CacheRow row = mapper.findCache(key);
        if (row != null && fresh(row, ttl)) {
            return parse(row.body);
        }
        String body = client.get(path);
        mapper.saveCache(key, body);
        return parse(body);
    }

    private static boolean fresh(ReviewMapper.CacheRow row, Duration ttl) {
        return row.fetchedAt != null && row.fetchedAt.isAfter(LocalDateTime.now().minus(ttl));
    }

    private JsonNode parse(String body) {
        try {
            return json.readTree(body);
        } catch (JsonProcessingException e) {
            throw new ReviewException(502, "OpenDota 返回的数据格式不对");
        }
    }
}

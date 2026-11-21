package com.dota.guide.review;

import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * MetaCollector —— 定时攒"高分局出装"样本
 *
 * 每隔一段时间（默认 1 小时）：
 *   1. 拉 OpenDota 的高分局公开比赛列表（/publicMatches?min_rank=80）；
 *   2. 只留天梯全英雄选择（lobby_type 7 + game_mode 22）、且没扫描过的；
 *   3. 逐场拉详情（默认每轮最多 25 场），把 10 个英雄的出装写进 meta_build；
 *   4. 删掉 30 天前的旧样本。
 *
 * 额度估算：每轮 1 + 25 = 26 次请求，一天 24 轮约 620 次，给复盘页留出大部分免费额度。
 * 不想跑可以在 application.yml 里把 review.meta.enabled 设成 false。
 */
@Component
public class MetaCollector {

    private static final Logger log = LoggerFactory.getLogger(MetaCollector.class);

    private static final int LOBBY_RANKED = 7;
    private static final int MODE_ALL_PICK_RANKED = 22;
    /** 15 分钟以内结束的一般是有人掉线、投降，出装不具代表性 */
    private static final int MIN_DURATION = 15 * 60;

    private final OpenDotaService od;
    private final ReviewMapper mapper;
    private final boolean enabled;
    private final int minRank;
    private final int perRun;

    public MetaCollector(OpenDotaService od, ReviewMapper mapper,
                         @Value("${review.meta.enabled:true}") boolean enabled,
                         @Value("${review.meta.min-rank:80}") int minRank,
                         @Value("${review.meta.matches-per-run:25}") int perRun) {
        this.od = od;
        this.mapper = mapper;
        this.enabled = enabled;
        this.minRank = minRank;
        this.perRun = perRun;
    }

    @Scheduled(initialDelayString = "${review.meta.initial-delay-ms:60000}",
            fixedDelayString = "${review.meta.interval-ms:3600000}")
    public void run() {
        if (!enabled) {
            return;
        }
        try {
            int saved = collectOnce();
            int removed = mapper.deleteBuildsBefore(System.currentTimeMillis() / 1000 - ReviewService.SAMPLE_DAYS * 86400L);
            log.info("高分局出装：本轮新增 {} 场，清理旧样本 {} 条", saved, removed);
        } catch (Exception e) {
            // 定时任务里的异常不能往外抛（会被 Spring 吞掉且看不到），记日志即可，下一轮再试
            log.warn("高分局出装采集失败：{}", e.getMessage());
        }
    }

    /** @return 本轮成功入库的比赛场数 */
    int collectOnce() {
        JsonNode list = od.publicMatches(minRank);
        List<Long> todo = new ArrayList<>();
        for (JsonNode m : list) {
            if (m.path("lobby_type").asInt() != LOBBY_RANKED || m.path("game_mode").asInt() != MODE_ALL_PICK_RANKED) {
                continue;
            }
            long id = m.path("match_id").asLong();
            if (mapper.countScanned(id) == 0) {
                todo.add(id);
            }
            if (todo.size() >= perRun) {
                break;
            }
        }

        int saved = 0;
        for (long id : todo) {
            try {
                JsonNode match = od.matchUncached(id);
                mapper.markScanned(id);   // 不管能不能用，都记为扫过，避免反复拉同一场
                if (saveMatch(match)) {
                    saved++;
                }
            } catch (ReviewException e) {
                if (e.getCode() == 429) {
                    log.warn("OpenDota 额度用完，本轮提前结束");
                    break;
                }
                // 刚打完的比赛 OpenDota 可能还没入库（404），下一轮列表里通常就没它了，跳过即可
                mapper.markScanned(id);
            }
        }
        return saved;
    }

    private boolean saveMatch(JsonNode m) {
        JsonNode players = m.path("players");
        int duration = m.path("duration").asInt();
        if (players.size() != 10 || duration < MIN_DURATION) {
            return false;
        }
        boolean radiantWin = m.path("radiant_win").asBoolean();
        for (JsonNode p : players) {
            int heroId = p.path("hero_id").asInt();
            if (heroId <= 0) {
                return false;
            }
            boolean radiant = p.path("player_slot").asInt() < 128;
            ReviewMapper.BuildInsert row = new ReviewMapper.BuildInsert();
            row.matchId = m.path("match_id").asLong();
            row.heroId = heroId;
            row.win = radiant == radiantWin;
            row.avgRankTier = m.path("avg_rank_tier").asInt(m.path("rank_tier").asInt(0));
            row.startTime = m.path("start_time").asLong();
            row.duration = duration;
            row.items = joinIds(ReviewService.finalItemIds(p));
            row.firstBuys = joinTimes(p.path("first_purchase_time"));
            mapper.insertBuild(row);
        }
        return true;
    }

    private static String joinIds(List<Integer> ids) {
        StringBuilder sb = new StringBuilder();
        for (int id : ids) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(id);
        }
        return sb.toString();
    }

    /** first_purchase_time {"blink":840, ...} → "blink:840,..."，超过列宽就截断 */
    private static String joinTimes(JsonNode node) {
        StringBuilder sb = new StringBuilder();
        Iterator<Map.Entry<String, JsonNode>> it = node.fields();
        while (it.hasNext()) {
            Map.Entry<String, JsonNode> e = it.next();
            if (!e.getValue().isNumber()) {
                continue;
            }
            String part = e.getKey() + ":" + e.getValue().asInt();
            if (sb.length() + part.length() + 1 > 2000) {
                break;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(part);
        }
        return sb.toString();
    }
}

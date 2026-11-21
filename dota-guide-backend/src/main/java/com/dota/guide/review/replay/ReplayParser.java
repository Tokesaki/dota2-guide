package com.dota.guide.review.replay;

import skadistats.clarity.model.CombatLogEntry;
import skadistats.clarity.model.Entity;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.OnEntityCreated;
import skadistats.clarity.processor.entities.OnEntityDeleted;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.gameevents.OnCombatLogEntry;
import skadistats.clarity.processor.reader.OnTickEnd;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.source.MappedFileSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * ReplayParser —— 用 clarity 读 .dem 录像，取复盘要用的原始数据，不做分析
 *
 * 取的东西（分析在 {@link ReplayAnalysis}，纯计算、好测试）：
 *   - 每秒一次 10 个英雄的位置和死活
 *   - 战斗日志：打到英雄身上的伤害、英雄阵亡（谁用什么杀的）、守卫被拆 / 到时间消失
 *   - 守卫：插下和消失的时间、位置、谁插的
 *   - 每整分钟一次每个人的身价、总收入、总经验、正补、反补、等级
 *
 * 录像里几个关键实体：
 *   CDOTAGamerulesProxy  —— 比赛开始时间（m_flGameStartTime）、暂停累计 tick，用来换算游戏时钟
 *   CDOTA_PlayerResource —— 每个玩家选的英雄 id、英雄实体句柄、Steam ID、队内位置（m_iTeamSlot）、等级
 *   CDOTA_DataRadiant / CDOTA_DataDire —— m_vecDataTeam.{队内位置}.m_iNetWorth / m_iTotalEarnedGold / ...
 *   CDOTA_Unit_Hero_*    —— 英雄本体：位置（cell * 128 + vec - 16384）、m_lifeState（0 = 活着）
 *   CDOTA_NPC_Observer_Ward（假眼）/ CDOTA_NPC_Observer_Ward_TrueSight（真眼）
 *
 * 游戏时钟 = (当前 tick - 已暂停的 tick) × 每 tick 秒数 - 比赛开始时间；
 * 暂停中用暂停开始那一刻的 tick，所以暂停期间时钟不走，和游戏里看到的时间一致。
 * 战斗日志的时间戳减比赛开始时间就是游戏里看到的时钟（和 OpenDota 的时间一致）。
 * 按 tick 算的时钟在有些录像里会差几秒（比如开局前暂停过），所以用前几十条战斗日志的时间
 * 校准一个固定偏移，位置采样、整分钟经济、守卫消失时间都用校准后的时钟。
 */
@UsesEntities
public class ReplayParser {

    /** 一名玩家：游戏内英雄 id、阵营（2 天辉 / 3 夜魇）、账号 id（Steam64 换算成 32 位，匿名时为 null） */
    public record PlayerInfo(int heroId, int team, Long accountId) {
    }

    /** 某一秒的采样：xs/ys/alive 都按 players 的顺序排 */
    public record Sample(int t, int[] xs, int[] ys, boolean[] alive) {
    }

    /**
     * 战斗日志里有用的一条。kind：DAMAGE 打到英雄身上的伤害 / DEATH 英雄阵亡 / WARD 守卫没了。
     * t 是游戏时钟（秒）。名字都是内部名：npc_dota_hero_xxx、技能 key、item_xxx；没有技能时 inflictor 为 null。
     */
    public record Combat(String kind, float t, String attacker, String target, String inflictor, int value) {
    }

    /** 一个守卫：sentry=true 是真眼；owner 是插眼人在 players 里的下标（-1 不知道）；end = -1 表示到比赛结束还在 */
    public record Ward(boolean sentry, int team, int owner, float start, float end, int x, int y) {
    }

    /** 整分钟时每个人的经济，数组按 players 的顺序 */
    public record Minute(int minute, int[] netWorth, int[] gold, int[] xp, int[] lastHits, int[] denies, int[] level) {
    }

    public record Raw(List<PlayerInfo> players, List<Sample> samples,
                      List<Combat> combat, List<Ward> wards, List<Minute> minutes) {
        /** 只有位置采样（老测试用） */
        public Raw(List<PlayerInfo> players, List<Sample> samples) {
            this(players, samples, List.of(), List.of(), List.of());
        }
    }

    private static final long STEAM64_BASE = 76561197960265728L;
    private static final String WARD_CLASSES = "CDOTA_NPC_Observer_Ward.*";

    private final List<PlayerInfo> players = new ArrayList<>();
    private final List<Integer> heroHandles = new ArrayList<>();
    private final List<Integer> playerSlots = new ArrayList<>();   // PlayerResource 里的下标
    private final List<Integer> teamSlots = new ArrayList<>();     // 队内位置 0-4
    private final List<Sample> samples = new ArrayList<>();
    private final List<Minute> minutes = new ArrayList<>();
    /** 战斗日志原始时间戳（还没减比赛开始时间），最后统一换算 */
    private final List<Combat> rawCombat = new ArrayList<>();
    /** 守卫句柄 → [sentry, team, ownerPlayerId, createTime, x, y]，消失时转成 Ward */
    private final Map<Integer, float[]> liveWards = new HashMap<>();
    private final List<float[]> deadWards = new ArrayList<>();
    private int lastSec = Integer.MIN_VALUE;
    private int rulesHandle = -1;
    private float gameStart = 0;
    private float clock = Float.NaN;
    /** 校准：战斗日志时钟 - tick 时钟，收前 CALIBRATE 条取中位数后固定 */
    private static final int CALIBRATE = 31;
    private final List<Float> offsets = new ArrayList<>();
    private float offset = 0;
    private boolean ended = false;

    public static Raw parse(Path dem) throws IOException {
        ReplayParser p = new ReplayParser();
        new SimpleRunner(new MappedFileSource(dem)).runWith(p);
        return p.build();
    }

    private Raw build() {
        List<Combat> combat = new ArrayList<>(rawCombat.size());
        for (Combat c : rawCombat) {
            combat.add(new Combat(c.kind(), c.t() - gameStart, c.attacker(), c.target(), c.inflictor(), c.value()));
        }
        List<Ward> wards = new ArrayList<>();
        for (float[] w : deadWards) {
            wards.add(toWard(w));
        }
        for (float[] w : liveWards.values()) {
            wards.add(toWard(w));
        }
        wards.sort((a, b) -> Float.compare(a.start(), b.start()));
        return new Raw(List.copyOf(players), List.copyOf(samples), combat, wards, List.copyOf(minutes));
    }

    private Ward toWard(float[] w) {
        // m_nPlayerOwnerID 是新版玩家编号：PlayerResource 下标 × 2
        int id = (int) w[2];
        int owner = id >= 0 && id % 2 == 0 ? playerSlots.indexOf(id / 2) : -1;
        float end = w.length > 6 ? w[6] : -1;
        return new Ward(w[0] > 0, (int) w[1], owner, w[3] - gameStart, end, Math.round(w[4]), Math.round(w[5]));
    }

    @OnCombatLogEntry
    public void onCombatLog(CombatLogEntry e) {
        if (!Float.isNaN(clock) && gameStart > 0 && offsets.size() < CALIBRATE) {
            offsets.add(e.getTimestamp() - gameStart - (clock - offset));
            List<Float> sorted = new ArrayList<>(offsets);
            sorted.sort(null);
            offset = sorted.get(sorted.size() / 2);
        }
        String type = e.getType().name();
        switch (type) {
            case "DOTA_COMBATLOG_DAMAGE" -> {
                if (e.isTargetHero() && !e.isTargetIllusion() && e.getValue() > 0) {
                    add("DAMAGE", e);
                }
            }
            case "DOTA_COMBATLOG_DEATH" -> {
                String target = e.getTargetName();
                if (e.isTargetHero() && !e.isTargetIllusion()) {
                    add("DEATH", e);
                } else if (target != null && (target.equals("npc_dota_observer_wards") || target.equals("npc_dota_sentry_wards"))) {
                    add("WARD", e);
                }
            }
            default -> {
            }
        }
    }

    private void add(String kind, CombatLogEntry e) {
        String inf = e.getInflictorName();
        if (inf == null || inf.equals("dota_unknown")) {
            inf = null;
        }
        rawCombat.add(new Combat(kind, e.getTimestamp(), e.getAttackerName(), e.getTargetName(), inf, e.getValue()));
    }

    @OnEntityCreated(classPattern = WARD_CLASSES)
    public void onWardCreated(Entity e) {
        float x = num(e, "CBodyComponent.m_cellX") * 128 + num(e, "CBodyComponent.m_vecX") - 16384;
        float y = num(e, "CBodyComponent.m_cellY") * 128 + num(e, "CBodyComponent.m_vecY") - 16384;
        boolean sentry = e.getDtClass().getDtName().endsWith("TrueSight");
        liveWards.put(e.getHandle(), new float[]{sentry ? 1 : 0, num(e, "m_iTeamNum"), num(e, "m_nPlayerOwnerID"),
                num(e, "m_flCreateTime"), x, y});
    }

    @OnEntityDeleted(classPattern = WARD_CLASSES)
    public void onWardDeleted(Entity e) {
        float[] w = liveWards.remove(e.getHandle());
        if (w == null || ended) {
            return;
        }
        float[] done = new float[7];
        System.arraycopy(w, 0, done, 0, 6);
        done[6] = Float.isNaN(clock) ? 0 : clock;
        deadWards.add(done);
    }

    @OnTickEnd
    public void onTickEnd(Context ctx, boolean synthetic) {
        Entities es = ctx.getProcessor(Entities.class);
        // 每个 tick 都要读时钟，所以 GameRules 实体只全表找一次，之后按句柄直接取
        Entity rules = rulesHandle < 0 ? null : es.getByHandle(rulesHandle);
        if (rules == null) {
            rules = first(es, "CDOTAGamerulesProxy");
            if (rules == null) {
                return;
            }
            rulesHandle = rules.getHandle();
        }
        float start = num(rules, "m_pGameRules.m_flGameStartTime");
        if (start <= 0 || ended) {
            return;   // 还没吹开局号角
        }
        if (num(rules, "m_pGameRules.m_flGameEndTime") > 0) {
            ended = true;   // 基地已经爆了，之后是赛后画面，不算；还在场上的眼算"到结束还在"
            return;
        }
        gameStart = start;
        int pausedTicks = (int) num(rules, "m_pGameRules.m_nTotalPausedTicks");
        boolean paused = Boolean.TRUE.equals(prop(rules, "m_pGameRules.m_bGamePaused"));
        int tick = paused ? (int) num(rules, "m_pGameRules.m_nPauseStartTick") : ctx.getTick();
        clock = (tick - pausedTicks) * ctx.getMillisPerTick() / 1000f - start + offset;
        int sec = (int) Math.floor(clock);
        if (sec <= lastSec) {
            return;
        }
        lastSec = sec;

        if (players.isEmpty() && !readPlayers(es)) {
            return;
        }
        int n = players.size();
        int[] xs = new int[n];
        int[] ys = new int[n];
        boolean[] alive = new boolean[n];
        for (int i = 0; i < n; i++) {
            Entity hero = es.getByHandle(heroHandles.get(i));
            if (hero == null) {
                continue;
            }
            xs[i] = Math.round(num(hero, "CBodyComponent.m_cellX") * 128 + num(hero, "CBodyComponent.m_vecX") - 16384);
            ys[i] = Math.round(num(hero, "CBodyComponent.m_cellY") * 128 + num(hero, "CBodyComponent.m_vecY") - 16384);
            alive[i] = num(hero, "m_lifeState") == 0;
        }
        samples.add(new Sample(sec, xs, ys, alive));
        if (sec >= 0 && sec % 60 == 0) {
            readMinute(es, sec / 60);
        }
    }

    private void readMinute(Entities es, int minute) {
        Entity pr = first(es, "CDOTA_PlayerResource");
        Entity rad = first(es, "CDOTA_DataRadiant");
        Entity dire = first(es, "CDOTA_DataDire");
        if (pr == null || rad == null || dire == null) {
            return;
        }
        int n = players.size();
        int[] nw = new int[n], gold = new int[n], xp = new int[n], lh = new int[n], dn = new int[n], lv = new int[n];
        for (int i = 0; i < n; i++) {
            Entity data = players.get(i).team() == 2 ? rad : dire;
            String k = String.format("m_vecDataTeam.%04d.", teamSlots.get(i));
            nw[i] = (int) num(data, k + "m_iNetWorth");
            gold[i] = (int) num(data, k + "m_iTotalEarnedGold");
            xp[i] = (int) num(data, k + "m_iTotalEarnedXP");
            lh[i] = (int) num(data, k + "m_iLastHitCount");
            dn[i] = (int) num(data, k + "m_iDenyCount");
            lv[i] = (int) num(pr, String.format("m_vecPlayerTeamData.%04d.m_iLevel", playerSlots.get(i)));
        }
        minutes.add(new Minute(minute, nw, gold, xp, lh, dn, lv));
    }

    /** 从 PlayerResource 读出 10 名玩家；英雄实体还没生成时返回 false，下一秒再试 */
    private boolean readPlayers(Entities es) {
        Entity pr = first(es, "CDOTA_PlayerResource");
        if (pr == null) {
            return false;
        }
        List<PlayerInfo> ps = new ArrayList<>();
        List<Integer> hs = new ArrayList<>();
        List<Integer> slots = new ArrayList<>();
        List<Integer> teamSlot = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            String team = String.format("m_vecPlayerTeamData.%04d.", i);
            if (!pr.hasProperty(team + "m_hSelectedHero")) {
                break;
            }
            int handle = (int) num(pr, team + "m_hSelectedHero");
            Entity hero = es.getByHandle(handle);
            if (hero == null) {
                continue;   // 观众、教练之类没有英雄的槽位
            }
            Object sid = prop(pr, String.format("m_vecPlayerData.%04d.m_iPlayerSteamID", i));
            Long account = null;
            if (sid instanceof Number s && s.longValue() > STEAM64_BASE) {
                account = s.longValue() - STEAM64_BASE;
            }
            ps.add(new PlayerInfo((int) num(pr, team + "m_nSelectedHeroID"), (int) num(hero, "m_iTeamNum"), account));
            hs.add(handle);
            slots.add(i);
            teamSlot.add((int) num(pr, team + "m_iTeamSlot"));
        }
        if (ps.size() < 10) {
            return false;
        }
        players.addAll(ps);
        heroHandles.addAll(hs);
        playerSlots.addAll(slots);
        teamSlots.addAll(teamSlot);
        return true;
    }

    private static Entity first(Entities es, String dtName) {
        return es.stream().filter(e -> dtName.equals(e.getDtClass().getDtName())).findFirst().orElse(null);
    }

    private static Object prop(Entity e, String name) {
        return e.hasProperty(name) ? e.getProperty(name) : null;
    }

    private static float num(Entity e, String name) {
        Object v = prop(e, name);
        return v instanceof Number n ? n.floatValue() : 0f;
    }
}

package com.dota.guide.review.replay;

import skadistats.clarity.model.Entity;
import skadistats.clarity.processor.entities.Entities;
import skadistats.clarity.processor.entities.UsesEntities;
import skadistats.clarity.processor.reader.OnTickEnd;
import skadistats.clarity.processor.runner.Context;
import skadistats.clarity.processor.runner.SimpleRunner;
import skadistats.clarity.source.MappedFileSource;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * ReplayParser —— 用 clarity 读 .dem 录像，按游戏时间每秒记录一次 10 个英雄的位置和死活
 *
 * 只取"原始采样"，不做任何分析（分析在 {@link ReplayAnalysis}，纯计算、好测试）。
 *
 * 录像里几个关键实体：
 *   CDOTAGamerulesProxy  —— 比赛开始时间（m_flGameStartTime）、暂停累计 tick，用来换算游戏时钟
 *   CDOTA_PlayerResource —— 每个玩家选的英雄 id（游戏内 hero_id）、英雄实体句柄、Steam ID
 *   CDOTA_Unit_Hero_*    —— 英雄本体：位置（cell * 128 + vec - 16384）、m_lifeState（0 = 活着）
 *
 * 游戏时钟 = (当前 tick - 已暂停的 tick) × 每 tick 秒数 - 比赛开始时间；
 * 暂停中用暂停开始那一刻的 tick，所以暂停期间时钟不走，和游戏里看到的时间一致。
 */
@UsesEntities
public class ReplayParser {

    /** 一名玩家：游戏内英雄 id、阵营（2 天辉 / 3 夜魇）、账号 id（Steam64 换算成 32 位，匿名时为 null） */
    public record PlayerInfo(int heroId, int team, Long accountId) {
    }

    /** 某一秒的采样：xs/ys/alive 都按 players 的顺序排 */
    public record Sample(int t, int[] xs, int[] ys, boolean[] alive) {
    }

    public record Raw(List<PlayerInfo> players, List<Sample> samples) {
    }

    private static final long STEAM64_BASE = 76561197960265728L;

    private final List<PlayerInfo> players = new ArrayList<>();
    private final List<Integer> heroHandles = new ArrayList<>();
    private final List<Sample> samples = new ArrayList<>();
    private int lastSec = Integer.MIN_VALUE;
    private int rulesHandle = -1;

    public static Raw parse(Path dem) throws IOException {
        ReplayParser p = new ReplayParser();
        new SimpleRunner(new MappedFileSource(dem)).runWith(p);
        return new Raw(List.copyOf(p.players), List.copyOf(p.samples));
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
        if (start <= 0 || num(rules, "m_pGameRules.m_flGameEndTime") > 0) {
            return;   // 还没吹开局号角，或者基地已经爆了（之后是赛后画面，不算）
        }
        int pausedTicks = (int) num(rules, "m_pGameRules.m_nTotalPausedTicks");
        boolean paused = Boolean.TRUE.equals(prop(rules, "m_pGameRules.m_bGamePaused"));
        int tick = paused ? (int) num(rules, "m_pGameRules.m_nPauseStartTick") : ctx.getTick();
        float clock = (tick - pausedTicks) * ctx.getMillisPerTick() / 1000f - start;
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
    }

    /** 从 PlayerResource 读出 10 名玩家；英雄实体还没生成时返回 false，下一秒再试 */
    private boolean readPlayers(Entities es) {
        Entity pr = first(es, "CDOTA_PlayerResource");
        if (pr == null) {
            return false;
        }
        List<PlayerInfo> ps = new ArrayList<>();
        List<Integer> hs = new ArrayList<>();
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
        }
        if (ps.size() < 10) {
            return false;
        }
        players.addAll(ps);
        heroHandles.addAll(hs);
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

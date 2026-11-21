package com.dota.guide.review.replay;

import com.dota.guide.review.replay.ReplayParser.Combat;
import com.dota.guide.review.replay.ReplayParser.Minute;
import com.dota.guide.review.replay.ReplayParser.PlayerInfo;
import com.dota.guide.review.replay.ReplayParser.Raw;
import com.dota.guide.review.replay.ReplayParser.Sample;
import com.dota.guide.review.replay.ReplayParser.Ward;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 造一局很小的假比赛：0-4 天辉（英雄 1-5），5-9 夜魇（英雄 6-10），slug 为 h1..h10。
 *   - 0、2、5、6、7 号一直站在 (0, 0) 附近，1 号在远处 (-6000, 6000) 活着，3 号从 120 秒起往中间走（晚到）
 *   - 100 / 106 / 112 秒夜魇 5、6、7 号接连阵亡，95 秒起双方英雄互相有伤害 → 一场团战
 *   - 5 号死前 5 秒被 0 号打了 300（技能 h1_nuke）、被小兵打了 20
 *   - 一个天辉假眼 50 秒插在 (0, 0)，200 秒被 9 号拆（战斗日志 197.5 秒，实体 200 秒才删）
 */
class ReplayInsightsTest {

    private static final int N = 10;

    @Test
    void findsFightAndWhoWasThere() {
        ReplayAnalysis.Result r = ReplayAnalysis.analyze(1, fakeGame(), slugs());
        assertEquals(1, r.fights().size());
        ReplayInsights.Fight f = r.fights().get(0);
        assertEquals(95, f.start(), "开打时间往前推到第一笔英雄伤害");
        assertEquals(List.of(5, 6, 7), f.deathOrder());
        assertEquals(0, f.radiantLost());
        assertEquals(3, f.direLost());

        assertTrue(f.presence().get(0).present());
        assertEquals(95, f.presence().get(0).arrive());
        assertFalse(f.presence().get(1).present(), "1 号在远处");
        assertEquals("上路", f.presence().get(1).zone());
        assertTrue(f.presence().get(3).present());
        assertTrue(f.presence().get(3).arrive() - f.start() >= 10, "3 号晚到");
    }

    @Test
    void deathDetailsComeFromCombatLog() {
        ReplayAnalysis.Result r = ReplayAnalysis.analyze(1, fakeGame(), slugs());
        List<ReplayAnalysis.Death> ds = r.players().get(5).deaths();
        assertEquals(1, ds.size());
        ReplayAnalysis.Death d = ds.get(0);
        assertEquals(100, d.t());
        assertEquals("npc_dota_hero_h1", d.killer());
        assertEquals(0, d.killerIndex());
        assertEquals("h1_nuke", d.inflictor());
        assertEquals(320, d.damage());
        assertEquals(0, d.hits().get(0).sourceIndex());
        assertEquals(300, d.hits().get(0).value());
        assertEquals(-1, d.hits().get(1).sourceIndex());
        assertEquals(1, d.enemyWards(), "死在天辉假眼旁边");
        assertEquals(0, d.fight());
    }

    @Test
    void wardsMatchCombatLogKiller() {
        ReplayAnalysis.Result r = ReplayAnalysis.analyze(1, fakeGame(), slugs());
        ReplayInsights.WardInfo w = r.wards().get(0);
        assertEquals(197, w.end(), "用战斗日志的时间");
        assertEquals("npc_dota_hero_h10", w.killer());
        assertEquals(9, w.killerIndex());
        ReplayInsights.WardInfo expired = r.wards().get(1);
        assertNull(expired.killer(), "到时间自己消失的不算被拆");
        assertEquals(1, r.players().get(9).wards().dewards());
        assertEquals(2, r.players().get(2).wards().observers());
        assertEquals(1, r.players().get(2).wards().observersDewarded());
    }

    @Test
    void economyAndIssues() {
        ReplayAnalysis.Result r = ReplayAnalysis.analyze(1, fakeGame(), slugs());
        ReplayInsights.Econ e = r.players().get(0).econ();
        assertEquals(15, e.netWorth().length);
        assertEquals(600 + 10 * 400, e.netWorth()[10]);
        // 1 号在远处闲逛：缺席 3 人阵亡的团战，应该出现在总结里
        List<ReplayInsights.Issue> issues = r.players().get(1).issues();
        assertTrue(issues.stream().anyMatch(i -> i.title().contains("没到")), issues.toString());
        assertTrue(issues.size() <= 3);
    }

    @Test
    void laneOfNeedsThreeMinutes() {
        List<String> zs = new ArrayList<>(List.of("己方基地", "中路", "中路", "中路", "河道", "下路"));
        assertEquals("中路", ReplayInsights.laneOf(zs));
        assertNull(ReplayInsights.laneOf(List.of("己方基地", "中路", "河道", "下路")));
    }

    // ------------------------------------------------------------------ 假数据

    private static Map<Integer, String> slugs() {
        Map<Integer, String> m = new HashMap<>();
        for (int i = 0; i < N; i++) {
            m.put(i + 1, "h" + (i + 1));
        }
        return m;
    }

    private static Raw fakeGame() {
        List<PlayerInfo> ps = new ArrayList<>();
        for (int i = 0; i < N; i++) {
            ps.add(new PlayerInfo(i + 1, i < 5 ? 2 : 3, null));
        }
        int[] deathAt = new int[N];
        deathAt[5] = 100;
        deathAt[6] = 106;
        deathAt[7] = 112;
        List<Sample> ss = new ArrayList<>();
        for (int t = 0; t <= 840; t++) {
            int[] xs = new int[N];
            int[] ys = new int[N];
            boolean[] alive = new boolean[N];
            for (int i = 0; i < N; i++) {
                alive[i] = deathAt[i] == 0 || t < deathAt[i] || t > deathAt[i] + 40;
                if (i == 1) {
                    xs[i] = -6000;
                    ys[i] = 6000;
                } else if (i == 3) {
                    int d = Math.max(0, 5000 - Math.max(0, t - 90) * 200);   // 115 秒左右进 2000 码
                    xs[i] = -d;
                    ys[i] = -d;
                } else if (i == 4 || i == 8 || i == 9) {
                    xs[i] = 6000;
                    ys[i] = -6000;
                } else {
                    xs[i] = 100 * (i % 3);
                    ys[i] = -100 * (i % 2);
                }
            }
            ss.add(new Sample(t, xs, ys, alive));
        }
        List<Combat> cs = new ArrayList<>();
        cs.add(new Combat("DAMAGE", 95.5f, "npc_dota_hero_h1", "npc_dota_hero_h6", "h1_nuke", 300));
        cs.add(new Combat("DAMAGE", 97f, "npc_dota_creep_goodguys_melee", "npc_dota_hero_h6", null, 20));
        cs.add(new Combat("DAMAGE", 96f, "npc_dota_hero_h6", "npc_dota_hero_h6", "item_x", 50));   // 自己打自己不算
        cs.add(new Combat("DAMAGE", 117f, "npc_dota_hero_h4", "npc_dota_hero_h8", null, 80));
        cs.add(new Combat("DEATH", 100.3f, "npc_dota_hero_h1", "npc_dota_hero_h6", "h1_nuke", 0));
        cs.add(new Combat("DEATH", 106.1f, "npc_dota_hero_h3", "npc_dota_hero_h7", null, 0));
        cs.add(new Combat("DEATH", 112.4f, "npc_dota_hero_h1", "npc_dota_hero_h8", null, 0));
        cs.add(new Combat("WARD", 197.5f, "npc_dota_hero_h10", "npc_dota_observer_wards", null, 0));
        cs.add(new Combat("WARD", 408f, "npc_dota_observer_wards", "npc_dota_observer_wards", null, 0));
        List<Ward> ws = List.of(
                new Ward(false, 2, 2, 50f, 200f, 0, 0),
                new Ward(false, 2, 2, 50f, 410f, 3000, 3000));
        List<Minute> ms = new ArrayList<>();
        for (int m = 0; m < 15; m++) {
            int[] nw = new int[N], gold = new int[N], xp = new int[N], lh = new int[N], dn = new int[N], lv = new int[N];
            for (int i = 0; i < N; i++) {
                nw[i] = 600 + m * (i == 1 ? 100 : 400);
                gold[i] = m * (i == 1 ? 100 : 400);
                xp[i] = m * 300;
                lh[i] = m * 5;
                lv[i] = 1 + m / 2;
            }
            ms.add(new Minute(m, nw, gold, xp, lh, dn, lv));
        }
        return new Raw(ps, ss, cs, ws, ms);
    }
}

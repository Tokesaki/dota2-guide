package com.dota.guide.review.replay;

import com.dota.guide.review.replay.ReplayParser.PlayerInfo;
import com.dota.guide.review.replay.ReplayParser.Raw;
import com.dota.guide.review.replay.ReplayParser.Sample;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReplayAnalysis —— 把每秒的位置采样变成复盘能看的东西（纯计算，不碰文件和网络）
 *
 *   - 区域时间占比：对线（上/中/下路）、己方野区、敌方野区、河道、己方基地、敌方高地，分 0-10 / 10-20 / 20+ 分钟
 *   - 每分钟主要待在哪（时间轴色条）
 *   - 抱团率：活着的时间里，身边 1500 码内至少有 2 个队友的比例
 *   - 热力图：40×40 格，活着时每秒在哪一格 +1
 *   - 每次死亡：死前最后一秒的位置、身边队友数、身边敌人数（不管看不看得见）
 *   - 回放轨迹：每 2 秒 10 个英雄的位置，给前端的小地图回放用
 *
 * 地图坐标：天辉基地在左下（约 -7000, -6600），夜魇在右上；河道大致是 x + y = 0 那条斜线，
 * 中路是 x = y 那条斜线。区域划分是按坐标粗略切的，图上标的是"估算"。
 */
public final class ReplayAnalysis {

    public static final int MAP_MIN = -8800;
    public static final int MAP_MAX = 8800;
    public static final int GRID = 40;
    static final int NEAR = 1500;
    static final int TRAIL_STEP = 2;

    /** 区域代号，前端按这个顺序和颜色画 */
    public static final List<String> ZONES = List.of("上路", "中路", "下路", "己方野区", "敌方野区", "河道", "己方基地", "敌方高地");

    public record Death(int t, int x, int y, String zone, int allies, int enemies) {
    }

    public record PhaseZones(String label, int seconds, Map<String, Double> share) {
    }

    public record PlayerAnalysis(int heroId, String slug, int team, Long accountId,
                                 Map<String, Double> zoneShare, List<PhaseZones> phases,
                                 List<String> minuteZones, double groupRate,
                                 int[] heat, List<Death> deaths) {
    }

    /**
     * @param trail 每 2 秒一帧：[t, x0, y0, alive0, x1, y1, alive1, ...]（alive 为 1/0）
     */
    public record Result(int version, long matchId, int duration, int mapMin, int mapMax, int grid,
                         List<String> zones, List<PlayerAnalysis> players, List<int[]> trail) {
    }

    private ReplayAnalysis() {
    }

    public static Result analyze(long matchId, Raw raw, Map<Integer, String> heroSlugs) {
        List<PlayerInfo> ps = raw.players();
        List<Sample> ss = raw.samples();
        int n = ps.size();
        List<PlayerAnalysis> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(analyzePlayer(i, ps, ss, heroSlugs));
        }
        List<int[]> trail = new ArrayList<>();
        for (Sample s : ss) {
            if (s.t() < 0 || s.t() % TRAIL_STEP != 0) {
                continue;
            }
            int[] f = new int[1 + 3 * n];
            f[0] = s.t();
            for (int i = 0; i < n; i++) {
                f[1 + 3 * i] = s.xs()[i];
                f[2 + 3 * i] = s.ys()[i];
                f[3 + 3 * i] = s.alive()[i] ? 1 : 0;
            }
            trail.add(f);
        }
        int duration = ss.isEmpty() ? 0 : Math.max(0, ss.get(ss.size() - 1).t());
        return new Result(1, matchId, duration, MAP_MIN, MAP_MAX, GRID, ZONES, out, trail);
    }

    private static PlayerAnalysis analyzePlayer(int i, List<PlayerInfo> ps, List<Sample> ss, Map<Integer, String> slugs) {
        PlayerInfo me = ps.get(i);
        boolean radiant = me.team() == 2;
        int[] heat = new int[GRID * GRID];
        long[][] phaseCount = new long[3][ZONES.size()];
        Map<Integer, long[]> perMinute = new LinkedHashMap<>();
        int aliveSec = 0;
        int groupedSec = 0;
        List<Death> deaths = new ArrayList<>();
        Sample prev = null;

        for (Sample s : ss) {
            if (s.t() < 0) {
                prev = s;
                continue;
            }
            boolean alive = s.alive()[i];
            if (prev != null && prev.t() >= 0 && prev.alive()[i] && !alive) {
                deaths.add(deathAt(i, ps, prev));
            }
            prev = s;
            if (!alive) {
                continue;
            }
            int x = s.xs()[i];
            int y = s.ys()[i];
            aliveSec++;
            heat[cell(y) * GRID + cell(x)]++;
            int z = zoneOf(x, y, radiant);
            int phase = s.t() < 600 ? 0 : s.t() < 1200 ? 1 : 2;
            phaseCount[phase][z]++;
            perMinute.computeIfAbsent(s.t() / 60, k -> new long[ZONES.size()])[z]++;
            if (countNear(i, ps, s, true) >= 2) {
                groupedSec++;
            }
        }

        long[] total = new long[ZONES.size()];
        List<PhaseZones> phases = new ArrayList<>();
        String[] labels = {"0-10 分钟", "10-20 分钟", "20 分钟以后"};
        for (int p = 0; p < 3; p++) {
            long sum = 0;
            for (int z = 0; z < ZONES.size(); z++) {
                total[z] += phaseCount[p][z];
                sum += phaseCount[p][z];
            }
            if (sum > 0) {
                phases.add(new PhaseZones(labels[p], (int) sum, shares(phaseCount[p], sum)));
            }
        }
        List<String> minuteZones = new ArrayList<>();
        int lastMinute = perMinute.keySet().stream().mapToInt(Integer::intValue).max().orElse(-1);
        for (int m = 0; m <= lastMinute; m++) {
            long[] c = perMinute.get(m);
            minuteZones.add(c == null ? null : ZONES.get(argMax(c)));
        }
        return new PlayerAnalysis(me.heroId(), slugs.get(me.heroId()), me.team(), me.accountId(),
                shares(total, aliveSec), phases, minuteZones,
                aliveSec == 0 ? 0 : (double) groupedSec / aliveSec, heat, deaths);
    }

    private static Death deathAt(int i, List<PlayerInfo> ps, Sample s) {
        int x = s.xs()[i];
        int y = s.ys()[i];
        return new Death(s.t() + 1, x, y, ZONES.get(zoneOf(x, y, ps.get(i).team() == 2)),
                countNear(i, ps, s, true), countNear(i, ps, s, false));
    }

    /** 第 i 个英雄身边 NEAR 码内活着的队友（sameTeam=true）或敌人的数量 */
    static int countNear(int i, List<PlayerInfo> ps, Sample s, boolean sameTeam) {
        int team = ps.get(i).team();
        int c = 0;
        for (int j = 0; j < ps.size(); j++) {
            if (j == i || !s.alive()[j] || (ps.get(j).team() == team) != sameTeam) {
                continue;
            }
            long dx = s.xs()[j] - s.xs()[i];
            long dy = s.ys()[j] - s.ys()[i];
            if (dx * dx + dy * dy <= (long) NEAR * NEAR) {
                c++;
            }
        }
        return c;
    }

    /**
     * 坐标 → 区域代号（ZONES 的下标）。判断顺序：基地 → 三条线 → 河道 → 野区。
     * 线路范围参考 7.42 地图：上路沿左边和上边，下路沿下边和右边，中路沿 x = y 的对角线。
     */
    static int zoneOf(int x, int y, boolean radiant) {
        boolean inRadiantBase = x < -4800 && y < -4400;
        boolean inDireBase = x > 4600 && y > 4000;
        if (inRadiantBase || inDireBase) {
            return inRadiantBase == radiant ? 6 : 7;
        }
        if ((x < -5200 && y > -3600) || (y > 5000 && x < 3600)) {
            return 0;
        }
        if ((y < -5200 && x > -3600) || (x > 5000 && y < 3600)) {
            return 2;
        }
        if (Math.abs(x - y) < 1300) {
            return 1;
        }
        if (Math.abs(x + y) < 1100) {
            return 5;
        }
        boolean radiantSide = x + y < 0;
        return radiantSide == radiant ? 3 : 4;
    }

    static int cell(int v) {
        int c = (int) ((long) (v - MAP_MIN) * GRID / (MAP_MAX - MAP_MIN));
        return Math.max(0, Math.min(GRID - 1, c));
    }

    private static Map<String, Double> shares(long[] counts, long sum) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int z = 0; z < ZONES.size(); z++) {
            m.put(ZONES.get(z), sum == 0 ? 0 : (double) counts[z] / sum);
        }
        return m;
    }

    private static int argMax(long[] c) {
        int best = 0;
        for (int i = 1; i < c.length; i++) {
            if (c[i] > c[best]) {
                best = i;
            }
        }
        return best;
    }
}

package com.dota.guide.review.replay;

import com.dota.guide.review.replay.ReplayParser.Combat;
import com.dota.guide.review.replay.ReplayParser.Minute;
import com.dota.guide.review.replay.ReplayParser.PlayerInfo;
import com.dota.guide.review.replay.ReplayParser.Raw;
import com.dota.guide.review.replay.ReplayParser.Sample;
import com.dota.guide.review.replay.ReplayParser.Ward;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReplayInsights —— 录像里"位置之外"的复盘：团战、死亡细节、经济曲线、插眼、总结（纯计算）
 *
 * 输入是 {@link ReplayParser} 的原始数据，名字对英雄用 "npc_dota_hero_" + slug 对上 players 的下标。
 *
 *   团战：英雄阵亡按时间聚类——相邻两次阵亡不超过 FIGHT_GAP 秒、离这团中心不超过 FIGHT_RADIUS 码，
 *         一团里至少 3 人阵亡，或 2 人阵亡但当时附近站了 5 个以上英雄，才算团战。
 *         开打时间：第一次阵亡往前找英雄互相打的伤害，间隔不超过 8 秒就继续往前推。
 *         谁到了：开打到结束期间进过中心 PRESENT_RADIUS 码内，或者打出 / 吃到过英雄伤害。
 *   死亡：战斗日志给出击杀者、技能，死前 5 秒每个来源打了多少伤害；附近 1600 码有没有对面的假眼。
 *   经济：每整分钟的身价、总收入、经验、正反补；对线对手 = 1-9 分钟同一条线上、队内身价排名最接近的敌人。
 *         低效时段：某分钟的收入低于自己全场平均每分钟收入的 SLOW_RATIO，连续 2 分钟以上算一段（8 分钟以后，前期本来就少）。
 *   插眼：每个眼插了多久、被谁拆的（战斗日志里守卫阵亡、时间对得上的那条），自己到时间消失的不算被拆。
 *   总结：按几条规则给问题打分，取分最高的 3 条。
 */
public final class ReplayInsights {

    static final int FIGHT_GAP = 18;
    static final int FIGHT_RADIUS = 3500;
    static final int PRESENT_RADIUS = 2000;
    static final int WARD_VISION = 1600;
    static final double SLOW_RATIO = 0.55;
    static final String HERO_PREFIX = "npc_dota_hero_";

    /** 某个来源在死前 5 秒打的伤害；sourceIndex 是英雄下标，小兵野怪塔等为 -1 */
    public record Hit(String source, int sourceIndex, String inflictor, int value) {
    }

    /** 某人在一场团战里：present 到没到、arrive 到场秒数（-1 没到）、没到时是死着还是在哪、离多远、打出 / 吃到的英雄伤害 */
    public record Presence(boolean present, int arrive, boolean dead, String zone, int distance, int dealt, int taken) {
    }

    /** deathOrder：按先后阵亡的英雄下标 */
    public record Fight(int start, int end, int x, int y, int radiantLost, int direLost,
                        List<Integer> deathOrder, List<Presence> presence) {
    }

    /** killer 为空表示到时间自己消失（或比赛结束还在，end = -1） */
    public record WardInfo(boolean sentry, int team, int owner, int start, int end, int x, int y,
                           String killer, int killerIndex) {
    }

    public record WardStats(int observers, int sentries, int observerAvgLife, int observersDewarded, int dewards) {
    }

    /** 每分钟一格，下标就是分钟 */
    public record Econ(int[] netWorth, int[] gold, int[] xp, int[] lastHits, int[] denies, int[] level) {
    }

    /** 一段低效时间：from 到 to 分钟（含），这段赚了多少、按平均该赚多少、阵亡了几秒、主要在哪 */
    public record SlowPeriod(int from, int to, int gold, int expected, int deadSeconds, String zone) {
    }

    /** 总结里的一条：title 一句话问题，detail 依据，advice 怎么改，score 越大越该先改 */
    public record Issue(String title, String detail, String advice, int score) {
    }

    private final List<PlayerInfo> ps;
    private final Map<Integer, Sample> byT = new HashMap<>();
    private final Map<String, Integer> heroIndex = new HashMap<>();
    private final List<Combat> damage = new ArrayList<>();
    private final List<Combat> deaths = new ArrayList<>();
    private final List<Combat> wardDeaths = new ArrayList<>();
    private final Raw raw;

    ReplayInsights(Raw raw, List<String> slugs) {
        this.raw = raw;
        this.ps = raw.players();
        for (Sample s : raw.samples()) {
            byT.put(s.t(), s);
        }
        for (int i = 0; i < slugs.size(); i++) {
            if (slugs.get(i) != null) {
                heroIndex.put(HERO_PREFIX + slugs.get(i), i);
            }
        }
        for (Combat c : raw.combat()) {
            switch (c.kind()) {
                case "DAMAGE" -> damage.add(c);
                case "DEATH" -> deaths.add(c);
                case "WARD" -> wardDeaths.add(c);
                default -> {
                }
            }
        }
        damage.sort(Comparator.comparingDouble(Combat::t));
        deaths.sort(Comparator.comparingDouble(Combat::t));
    }

    boolean hasCombat() {
        return !raw.combat().isEmpty();
    }

    int indexOf(String name) {
        return name == null ? -1 : heroIndex.getOrDefault(name, -1);
    }

    // ---------------------------------------------------------------- 位置工具

    /** t 秒（含）以前最近一个 i 还活着的采样，往前最多找 4 秒 */
    Sample aliveSampleBefore(int i, int t) {
        for (int k = t; k >= t - 4; k--) {
            Sample s = byT.get(k);
            if (s != null && s.alive()[i]) {
                return s;
            }
        }
        return byT.get(t);
    }

    static double dist(int x1, int y1, int x2, int y2) {
        return Math.hypot(x1 - x2, y1 - y2);
    }

    // ---------------------------------------------------------------- 团战

    List<Fight> fights() {
        // 每次英雄阵亡：时间、谁、在哪
        List<int[]> ds = new ArrayList<>();   // [t, victim, x, y]
        for (Combat c : deaths) {
            int v = indexOf(c.target());
            if (v < 0) {
                continue;
            }
            int t = (int) Math.floor(c.t());
            Sample s = aliveSampleBefore(v, t);
            if (s == null) {
                continue;
            }
            ds.add(new int[]{t, v, s.xs()[v], s.ys()[v]});
        }
        List<Fight> out = new ArrayList<>();
        List<int[]> cur = new ArrayList<>();
        for (int[] d : ds) {
            if (!cur.isEmpty()) {
                int[] last = cur.get(cur.size() - 1);
                double[] c = centroid(cur);
                if (d[0] - last[0] > FIGHT_GAP || dist(d[2], d[3], (int) c[0], (int) c[1]) > FIGHT_RADIUS) {
                    addFight(cur, out);
                    cur = new ArrayList<>();
                }
            }
            cur.add(d);
        }
        addFight(cur, out);
        return out;
    }

    private static double[] centroid(List<int[]> ds) {
        double x = 0, y = 0;
        for (int[] d : ds) {
            x += d[2];
            y += d[3];
        }
        return new double[]{x / ds.size(), y / ds.size()};
    }

    private void addFight(List<int[]> cur, List<Fight> out) {
        if (cur.isEmpty()) {
            return;
        }
        double[] c = centroid(cur);
        int cx = (int) c[0], cy = (int) c[1];
        int first = cur.get(0)[0];
        int last = cur.get(cur.size() - 1)[0];
        if (cur.size() < 3) {
            if (cur.size() < 2 || heroesNear(first - 2, cx, cy) < 5) {
                return;
            }
        }
        // 开打：第一次阵亡往前找中心附近英雄之间的伤害，间隔 8 秒以内就继续往前
        double start = first;
        for (int k = damage.size() - 1; k >= 0; k--) {
            Combat d = damage.get(k);
            if (d.t() > first) {
                continue;
            }
            if (d.t() < start - 8 || d.t() < first - 40) {
                break;
            }
            int a = indexOf(d.attacker());
            int v = indexOf(d.target());
            if (a < 0 || v < 0 || ps.get(a).team() == ps.get(v).team()) {
                continue;
            }
            Sample s = byT.get((int) d.t());
            if (s != null && dist(s.xs()[v], s.ys()[v], cx, cy) <= FIGHT_RADIUS) {
                start = d.t();
            }
        }
        int st = (int) Math.floor(start);
        int end = last + 3;
        List<Integer> order = new ArrayList<>();
        int rad = 0, dire = 0;
        for (int[] d : cur) {
            order.add(d[1]);
            if (ps.get(d[1]).team() == 2) {
                rad++;
            } else {
                dire++;
            }
        }
        List<Presence> presence = new ArrayList<>();
        for (int j = 0; j < ps.size(); j++) {
            presence.add(presence(j, st, end, cx, cy));
        }
        out.add(new Fight(st, end, cx, cy, rad, dire, order, presence));
    }

    private int heroesNear(int t, int x, int y) {
        Sample s = byT.get(t);
        if (s == null) {
            return 0;
        }
        int n = 0;
        for (int j = 0; j < ps.size(); j++) {
            if (s.alive()[j] && dist(s.xs()[j], s.ys()[j], x, y) <= PRESENT_RADIUS) {
                n++;
            }
        }
        return n;
    }

    private Presence presence(int j, int start, int end, int cx, int cy) {
        int arrive = -1;
        for (int t = start; t <= end && arrive < 0; t++) {
            Sample s = byT.get(t);
            if (s != null && s.alive()[j] && dist(s.xs()[j], s.ys()[j], cx, cy) <= PRESENT_RADIUS) {
                arrive = t;
            }
        }
        int dealt = 0, taken = 0;
        for (Combat d : damage) {
            if (d.t() < start || d.t() > end) {
                continue;
            }
            int a = indexOf(d.attacker());
            int v = indexOf(d.target());
            if (a < 0 || v < 0 || ps.get(a).team() == ps.get(v).team()) {
                continue;
            }
            if (a == j) {
                dealt += d.value();
            }
            if (v == j) {
                taken += d.value();
            }
            if ((a == j || v == j) && (arrive < 0 || d.t() < arrive)) {
                arrive = (int) Math.floor(d.t());
            }
        }
        Sample s0 = byT.get(start);
        boolean dead = s0 != null && !s0.alive()[j];
        String zone = null;
        int distance = 0;
        if (s0 != null) {
            zone = ReplayAnalysis.ZONES.get(ReplayAnalysis.zoneOf(s0.xs()[j], s0.ys()[j], ps.get(j).team() == 2));
            distance = (int) dist(s0.xs()[j], s0.ys()[j], cx, cy);
        }
        return new Presence(arrive >= 0, arrive, dead, zone, distance, dealt, taken);
    }

    // ---------------------------------------------------------------- 死亡细节

    /** 第 i 个英雄每次阵亡的详细信息；fights 用来标这次死亡属于哪场团战 */
    List<ReplayAnalysis.Death> deathsOf(int i, List<Fight> fights, List<WardInfo> wards) {
        List<ReplayAnalysis.Death> out = new ArrayList<>();
        boolean radiant = ps.get(i).team() == 2;
        for (Combat c : deaths) {
            if (indexOf(c.target()) != i) {
                continue;
            }
            int t = (int) Math.floor(c.t());
            Sample s = aliveSampleBefore(i, t);
            if (s == null) {
                continue;
            }
            int x = s.xs()[i], y = s.ys()[i];
            // 死前 5 秒：按来源汇总伤害，记下每个来源打得最多的技能
            Map<String, int[]> bySource = new LinkedHashMap<>();
            Map<String, Map<String, Integer>> byInflictor = new HashMap<>();
            int total = 0;
            for (Combat d : damage) {
                if (d.t() < c.t() - 5 || d.t() > c.t() + 0.05 || !c.target().equals(d.target())
                        || c.target().equals(d.attacker())) {   // 自己打自己（血精石、反伤之类）不算
                    continue;
                }
                String src = d.attacker() == null ? "?" : d.attacker();
                bySource.computeIfAbsent(src, k -> new int[1])[0] += d.value();
                byInflictor.computeIfAbsent(src, k -> new HashMap<>())
                        .merge(d.inflictor() == null ? "" : d.inflictor(), d.value(), Integer::sum);
                total += d.value();
            }
            List<Hit> hits = new ArrayList<>();
            bySource.forEach((src, v) -> {
                String inf = byInflictor.get(src).entrySet().stream()
                        .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse("");
                hits.add(new Hit(src, indexOf(src), inf.isEmpty() ? null : inf, v[0]));
            });
            hits.sort((a, b) -> b.value() - a.value());
            int enemyWards = 0;
            for (WardInfo w : wards) {
                if (!w.sentry() && w.team() != ps.get(i).team() && w.start() <= t && (w.end() < 0 || w.end() >= t)
                        && dist(w.x(), w.y(), x, y) <= WARD_VISION) {
                    enemyWards++;
                }
            }
            int fight = -1;
            for (int f = 0; f < fights.size(); f++) {
                if (fights.get(f).deathOrder().contains(i) && t >= fights.get(f).start() && t <= fights.get(f).end()) {
                    fight = f;
                }
            }
            out.add(new ReplayAnalysis.Death(t, x, y, ReplayAnalysis.ZONES.get(ReplayAnalysis.zoneOf(x, y, radiant)),
                    ReplayAnalysis.countNear(i, ps, s, true), ReplayAnalysis.countNear(i, ps, s, false),
                    c.attacker(), indexOf(c.attacker()), c.inflictor(), total,
                    hits.size() > 4 ? List.copyOf(hits.subList(0, 4)) : hits, enemyWards, fight));
        }
        return out;
    }

    // ---------------------------------------------------------------- 经济

    Econ econOf(int i) {
        List<Minute> ms = raw.minutes();
        int n = ms.isEmpty() ? 0 : ms.get(ms.size() - 1).minute() + 1;
        int[] nw = new int[n], gold = new int[n], xp = new int[n], lh = new int[n], dn = new int[n], lv = new int[n];
        for (Minute m : ms) {
            int k = m.minute();
            nw[k] = m.netWorth()[i];
            gold[k] = m.gold()[i];
            xp[k] = m.xp()[i];
            lh[k] = m.lastHits()[i];
            dn[k] = m.denies()[i];
            lv[k] = m.level()[i];
        }
        return new Econ(nw, gold, xp, lh, dn, lv);
    }

    /** 1-9 分钟待得最多的线（上路 / 中路 / 下路），少于 3 分钟在线上就算游走，返回 null */
    static String laneOf(List<String> minuteZones) {
        Map<String, Integer> c = new HashMap<>();
        for (int m = 1; m < Math.min(10, minuteZones.size()); m++) {
            String z = minuteZones.get(m);
            if ("上路".equals(z) || "中路".equals(z) || "下路".equals(z)) {
                c.merge(z, 1, Integer::sum);
            }
        }
        return c.entrySet().stream().filter(e -> e.getValue() >= 3)
                .max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }

    /** 对线对手：同一条线上的敌人里，10 分钟身价在队内排名和自己最接近的；没人同线就按排名找 */
    int opponentOf(int i, List<String> lanes, List<Econ> econ) {
        int m = Math.min(10, econ.get(i).netWorth().length - 1);
        if (m < 0) {
            return -1;
        }
        int myRank = rank(i, econ, m);
        int best = -1;
        int bestScore = Integer.MAX_VALUE;
        for (int j = 0; j < ps.size(); j++) {
            if (ps.get(j).team() == ps.get(i).team()) {
                continue;
            }
            boolean sameLane = lanes.get(i) != null && lanes.get(i).equals(lanes.get(j));
            int score = Math.abs(rank(j, econ, m) - myRank) + (sameLane ? 0 : 10);
            if (score < bestScore) {
                bestScore = score;
                best = j;
            }
        }
        return best;
    }

    private int rank(int i, List<Econ> econ, int m) {
        int r = 0;
        for (int j = 0; j < ps.size(); j++) {
            if (ps.get(j).team() == ps.get(i).team() && econ.get(j).netWorth()[m] > econ.get(i).netWorth()[m]) {
                r++;
            }
        }
        return r;
    }

    List<SlowPeriod> slowPeriods(int i, Econ e, List<String> minuteZones) {
        int[] gold = e.gold();
        int n = gold.length;
        List<SlowPeriod> out = new ArrayList<>();
        if (n < 12) {
            return out;
        }
        double avg = (double) gold[n - 1] / (n - 1);
        int from = -1;
        for (int m = 9; m <= n; m++) {   // 前 8 分钟本来就赚得少，不算
            boolean slow = m < n && gold[m] - gold[m - 1] < avg * SLOW_RATIO;
            if (slow && from < 0) {
                from = m;
            } else if (!slow && from >= 0) {
                int to = m - 1;
                if (to - from + 1 >= 2) {
                    out.add(slowPeriod(i, from, to, gold, avg, minuteZones));
                }
                from = -1;
            }
        }
        return out;
    }

    private SlowPeriod slowPeriod(int i, int from, int to, int[] gold, double avg, List<String> minuteZones) {
        int dead = 0;
        for (int t = (from - 1) * 60; t < to * 60; t++) {
            Sample s = byT.get(t);
            if (s != null && !s.alive()[i]) {
                dead++;
            }
        }
        Map<String, Integer> zc = new HashMap<>();
        for (int m = from - 1; m < to && m < minuteZones.size(); m++) {
            if (m >= 0 && minuteZones.get(m) != null) {
                zc.merge(minuteZones.get(m), 1, Integer::sum);
            }
        }
        String zone = zc.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
        return new SlowPeriod(from - 1, to, gold[to] - gold[from - 1], (int) Math.round(avg * (to - from + 1)), dead, zone);
    }

    // ---------------------------------------------------------------- 插眼

    List<WardInfo> wards() {
        List<WardInfo> out = new ArrayList<>();
        boolean[] used = new boolean[wardDeaths.size()];
        for (Ward w : raw.wards()) {
            String killer = null;
            int end = w.end() < 0 ? -1 : Math.round(w.end());
            if (w.end() >= 0) {
                String type = w.sentry() ? "npc_dota_sentry_wards" : "npc_dota_observer_wards";
                // 实体比战斗日志里的"阵亡"晚 2~3 秒才删掉，所以往前找 5 秒内最近的一条
                int best = -1;
                double bestGap = 5;
                for (int k = 0; k < wardDeaths.size(); k++) {
                    Combat c = wardDeaths.get(k);
                    double gap = w.end() - c.t();
                    if (!used[k] && type.equals(c.target()) && gap >= -0.5 && gap <= bestGap) {
                        best = k;
                        bestGap = gap;
                    }
                }
                if (best >= 0) {
                    used[best] = true;
                    end = (int) Math.floor(wardDeaths.get(best).t());
                    String a = wardDeaths.get(best).attacker();
                    if (a != null && !a.equals(type)) {
                        killer = a;
                    }
                }
            }
            out.add(new WardInfo(w.sentry(), w.team(), w.owner(), Math.round(w.start()), end, w.x(), w.y(),
                    killer, indexOf(killer)));
        }
        return out;
    }

    static WardStats wardStats(int i, List<WardInfo> wards, int duration) {
        int obs = 0, sentries = 0, life = 0, dewarded = 0, dewards = 0;
        for (WardInfo w : wards) {
            if (w.owner() == i) {
                if (w.sentry()) {
                    sentries++;
                } else {
                    obs++;
                    life += (w.end() < 0 ? duration : w.end()) - Math.max(0, w.start());
                    if (w.killer() != null) {
                        dewarded++;
                    }
                }
            }
            if (w.killerIndex() == i) {
                dewards++;
            }
        }
        return new WardStats(obs, sentries, obs == 0 ? 0 : life / obs, dewarded, dewards);
    }

    // ---------------------------------------------------------------- 总结

    static List<Issue> issues(int i, int[] teams, List<Fight> fights, List<ReplayAnalysis.Death> myDeaths,
                              Econ me, Econ opp, String oppName, List<SlowPeriod> slow, WardStats ws) {
        List<Issue> out = new ArrayList<>();

        // 1. 没到的团战（活着、没参与）
        List<String> missed = new ArrayList<>();
        List<String> late = new ArrayList<>();
        int lateTotal = 0;
        int firstDie = 0;
        int fightsWith = 0;
        for (Fight f : fights) {
            Presence p = f.presence().get(i);
            if (!p.present()) {
                if (!p.dead() && f.deathOrder().size() >= 3) {   // 两个人的小规模遭遇不算你缺席
                    missed.add(clock(f.start()) + (p.zone() == null ? "" : " 你在" + p.zone()) + "（离 " + p.distance() / 100 * 100 + " 码）");
                }
            } else {
                fightsWith++;
                int l = p.arrive() - f.start();
                if (l >= 10) {
                    late.add(clock(f.start()) + " 晚到 " + l + " 秒");
                    lateTotal += l;
                }
                // 自己这边第一个死
                for (int v : f.deathOrder()) {
                    if (teams[v] == teams[i]) {
                        if (v == i) {
                            firstDie++;
                        }
                        break;
                    }
                }
            }
        }
        if (!missed.isEmpty()) {
            out.add(new Issue("有 " + missed.size() + " 场团战你活着但没到",
                    String.join("；", head(missed, 4)),
                    "看到队友往一个点集合、魔方 / 肉山快刷的时候提前靠过去，刷钱别刷到离团战太远的地方。",
                    25 * missed.size()));
        }
        if (late.size() >= 2) {
            out.add(new Issue("团战到得晚：" + late.size() + " 场晚到 10 秒以上",
                    String.join("；", head(late, 4)),
                    "多看小地图，队友开始打的时候就动身；带好回城卷轴，远的话直接 TP。",
                    lateTotal / 2));
        }
        if (firstDie >= 2) {
            out.add(new Issue("团战里你经常是己方第一个倒下的（" + firstDie + " 次）",
                    "参加的 " + fightsWith + " 场团战里，有 " + firstDie + " 场你是己方第一个死的",
                    "开打前站位往后一点，等对面关键技能交了再进；没保命装备的时候别第一个冲。",
                    18 * firstDie));
        }

        // 2. 被单抓：身边没队友、不在团战里
        int alone = 0, seen = 0;
        List<String> aloneAt = new ArrayList<>();
        for (ReplayAnalysis.Death d : myDeaths) {
            if (d.allies() == 0 && d.fight() < 0) {
                alone++;
                if (d.enemyWards() > 0) {
                    seen++;
                }
                aloneAt.add(clock(d.t()) + " " + d.zone());
            }
        }
        if (alone >= 2) {
            out.add(new Issue("一个人被抓死 " + alone + " 次",
                    String.join("；", head(aloneAt, 5)) + (seen > 0 ? "。其中 " + seen + " 次死的地方在对面假眼的视野里" : ""),
                    "去危险区域（敌方野区、没视野的河道）前先看对面几个人在不在小地图上；对面消失 3 个以上就收着点。"
                            + (seen > 0 ? "常走的路线插个真眼排一下。" : ""),
                    22 * alone));
        }

        // 3. 对线
        if (opp != null && me.lastHits().length > 10 && opp.lastHits().length > 10) {
            int lhDiff = me.lastHits()[10] - opp.lastHits()[10];
            int nwDiff = me.netWorth()[10] - opp.netWorth()[10];
            if (lhDiff <= -15 || nwDiff <= -1200) {
                out.add(new Issue("对线输给 " + oppName,
                        "10 分钟正补 " + me.lastHits()[10] + " 比 " + opp.lastHits()[10] + "，身价差 " + nwDiff,
                        "对线期优先补刀，打不过就别硬换血；被压得太厉害可以早点换线或者去拉野。",
                        Math.max(15, -lhDiff + Math.max(0, -nwDiff) / 100)));
            }
        }

        // 4. 低效时段
        int slowMin = 0;
        List<String> slowAt = new ArrayList<>();
        for (SlowPeriod s : slow) {
            slowMin += s.to() - s.from();
            slowAt.add(s.from() + "-" + s.to() + " 分钟只赚了 " + s.gold() + "（平时 " + s.expected() + "）"
                    + (s.deadSeconds() >= 30 ? "，其中阵亡 " + s.deadSeconds() + " 秒" : s.zone() == null ? "" : "，主要在" + s.zone()));
        }
        if (slowMin >= 4) {
            out.add(new Issue("有 " + slowMin + " 分钟几乎没赚钱",
                    String.join("；", head(slowAt, 3)),
                    "没团战的时候别在地图上闲逛：附近有兵线就吃线，没有就刷野，路上顺便带一下。",
                    7 * slowMin));
        }

        // 5. 插眼（只对插过 4 个以上假眼的人说）
        if (ws.observers() >= 4 && ws.observersDewarded() * 2 >= ws.observers()) {
            out.add(new Issue("假眼一半以上被拆了（" + ws.observersDewarded() + "/" + ws.observers() + "）",
                    "平均每个假眼只活了 " + ws.observerAvgLife() / 60 + " 分 " + ws.observerAvgLife() % 60 + " 秒（满的是 6 分钟）",
                    "别插在常见眼位的正中心，换到高台边缘或树林里；对面刚拆过眼的地方隔一会儿再插。",
                    6 * ws.observersDewarded()));
        }

        out.sort((a, b) -> b.score() - a.score());
        return out.size() > 3 ? List.copyOf(out.subList(0, 3)) : out;
    }

    private static List<String> head(List<String> xs, int n) {
        return xs.size() > n ? xs.subList(0, n) : xs;
    }

    static String clock(int t) {
        int a = Math.abs(t);
        return (t < 0 ? "-" : "") + a / 60 + ":" + String.format("%02d", a % 60);
    }
}

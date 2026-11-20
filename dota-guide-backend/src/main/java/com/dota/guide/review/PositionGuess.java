package com.dota.guide.review;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PositionGuess —— 推测每个玩家这局实际打的是几号位
 *
 * Dota 里没有"选号位"这个操作，比赛数据也不直接记录号位，只能推测：
 *
 * 1. 比赛被 OpenDota 解析过 → 有 lane_role（1 优势路 / 2 中路 / 3 劣势路 / 4 打野）：
 *      中路 → 2 号位；
 *      优势路两个人里经济高的 → 1 号位，低的 → 5 号位；
 *      劣势路两个人里经济高的 → 3 号位，低的 → 4 号位。
 * 2. 没解析过 → 只能按队内经济排名：经济第 1 当 1 号位、第 2 当 2 号位……
 *    这个很粗糙（中单经济经常比 1 号位高），页面上会标"推测"。
 *
 * 纯函数，单元测试见 PositionGuessTest。
 */
public final class PositionGuess {

    private PositionGuess() {
    }

    /**
     * @param index    玩家在比赛 players 数组里的下标（0..9）
     * @param radiant  是否天辉
     * @param netWorth 最终经济
     * @param laneRole OpenDota 的 lane_role，没解析时为 null
     */
    public record Slot(int index, boolean radiant, int netWorth, Integer laneRole) {
    }

    /** @return 下标 → 推测号位（1..5） */
    public static Map<Integer, Integer> guess(List<Slot> slots) {
        Map<Integer, Integer> out = new HashMap<>();
        for (boolean radiant : new boolean[]{true, false}) {
            List<Slot> team = new ArrayList<>();
            for (Slot s : slots) {
                if (s.radiant() == radiant) {
                    team.add(s);
                }
            }
            team.sort(Comparator.comparingInt(Slot::netWorth).reversed());
            boolean allHaveLane = !team.isEmpty() && team.stream().allMatch(s -> s.laneRole() != null);
            if (allHaveLane) {
                guessByLane(team, out);
            } else {
                for (int i = 0; i < team.size(); i++) {
                    out.put(team.get(i).index(), Math.min(i + 1, 5));
                }
            }
        }
        return out;
    }

    /** team 已经按经济从高到低排好 */
    private static void guessByLane(List<Slot> team, Map<Integer, Integer> out) {
        Set<Integer> taken = new HashSet<>();
        List<Slot> leftover = new ArrayList<>();
        int safeSeen = 0;
        int offSeen = 0;
        boolean midTaken = false;
        for (Slot s : team) {
            Integer pos = null;
            switch (s.laneRole()) {
                case 1 -> pos = (safeSeen++ == 0) ? 1 : 5;
                case 2 -> {
                    if (!midTaken) {
                        midTaken = true;
                        pos = 2;
                    }
                }
                case 3 -> pos = (offSeen++ == 0) ? 3 : 4;
                default -> {
                    // 打野或者未知，后面按剩下的号位补
                }
            }
            if (pos != null && taken.add(pos)) {
                out.put(s.index(), pos);
            } else {
                leftover.add(s);
            }
        }
        // 没分到的（打野、三人路之类）按经济顺序补上空着的号位
        int next = 1;
        for (Slot s : leftover) {
            while (taken.contains(next) && next < 5) {
                next++;
            }
            taken.add(next);
            out.put(s.index(), next);
        }
    }

    private static final Pattern POS = Pattern.compile("(\\d(?:/\\d)*)号位");

    /** 图鉴里的号位文案 → 号位集合："4/5号位" → {4,5}，"1号位、2号位" → {1,2} */
    public static Set<Integer> parseRecommended(String posText) {
        Set<Integer> set = new HashSet<>();
        if (posText == null) {
            return set;
        }
        Matcher m = POS.matcher(posText);
        while (m.find()) {
            for (String d : m.group(1).split("/")) {
                set.add(Integer.parseInt(d));
            }
        }
        return set;
    }
}

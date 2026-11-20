package com.dota.guide.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * ReviewRules —— 复盘建议的规则表：看一个玩家这局的数据，给出几条"做得好 / 要注意"的话
 *
 * 全部是写死的规则，不是 AI。好处是每条建议都能说清楚"为什么这么说"，
 * 坏处是只能覆盖常见问题。阈值都集中在这个类里，想调就改这里。
 *
 * 百分位（pct）来自 OpenDota 的 benchmarks：0.3 表示"超过全服 30% 的同英雄玩家"。
 *
 * 纯函数，单元测试见 ReviewRulesTest。
 */
public final class ReviewRules {

    private ReviewRules() {
    }

    /** good = 做得好，warn = 可以更好，bad = 明显问题，info = 提示 */
    public record Advice(String level, String text) {
    }

    /** 晚了多少秒才算"出装慢" */
    static final int LATE_SECONDS = 5 * 60;

    /** 规则需要的全部事实，由 ReviewService 从比赛数据里整理出来 */
    public record Facts(
            int pos,                      // 推测号位 1..5
            boolean posFromLane,          // 号位是否根据分路推出来的（否则是按经济粗略推测）
            Set<Integer> recommendedPos,  // 图鉴推荐号位
            String heroName,
            int durationSec,
            int deaths,
            Integer lastHits10,           // 10 分钟正补，没解析时为 null
            Map<String, Double> pct,      // benchmarks 百分位，key 如 gold_per_min
            List<String> missingCore,     // 高分局常出、你没出的大件（中文名）
            List<LateItem> lateItems      // 出得明显比高分局慢的物品
    ) {
    }

    public record LateItem(String name, int yourTime, int avgTime) {
    }

    public static List<Advice> advise(Facts f) {
        List<Advice> out = new ArrayList<>();
        boolean core = f.pos() <= 3;
        double minutes = Math.max(f.durationSec() / 60.0, 1);

        // ---------- 号位 ----------
        if (!f.recommendedPos().isEmpty() && !f.recommendedPos().contains(f.pos())) {
            String rec = f.recommendedPos().stream().sorted().map(String::valueOf).collect(Collectors.joining("/"));
            out.add(new Advice("info", String.format("这局你%s打的是 %d 号位，图鉴推荐%s打 %s 号位，打法建议可能要灵活参考。",
                    f.posFromLane() ? "" : "大概", f.pos(), f.heroName(), rec)));
        }

        // ---------- 发育 ----------
        Double gpm = f.pct().get("gold_per_min");
        if (gpm != null) {
            if (core && gpm < 0.3) {
                out.add(new Advice("bad", String.format("经济（GPM）只超过 %s 的同英雄玩家。核心位发育偏慢，多想想空闲时间有没有在刷钱。", pctText(gpm))));
            } else if (gpm >= 0.7) {
                out.add(new Advice("good", String.format("经济（GPM）超过 %s 的同英雄玩家，发育很好。", pctText(gpm))));
            }
        }
        Double lh = f.pct().get("last_hits_per_min");
        if (core && lh != null && lh < 0.3) {
            out.add(new Advice("warn", String.format("补刀速度只超过 %s 的同英雄玩家，对线和带线时多练正补。", pctText(lh))));
        }
        if (f.lastHits10() != null) {
            int target = switch (f.pos()) {
                case 1, 2 -> 40;
                case 3 -> 25;
                default -> -1;
            };
            if (target > 0 && f.lastHits10() < target) {
                out.add(new Advice("warn", String.format("10 分钟正补 %d 个，%d 号位一般要 %d 个以上，对线期可以更专注补刀。",
                        f.lastHits10(), f.pos(), target)));
            }
        }

        // ---------- 参战 ----------
        Double dmg = f.pct().get("hero_damage_per_min");
        if (core && dmg != null && dmg < 0.25) {
            out.add(new Advice("warn", String.format("英雄伤害只超过 %s 的同英雄玩家，团战时输出位置或参团时机可以再看看。", pctText(dmg))));
        }
        Double ast = f.pct().get("assists_per_min");
        if (!core && ast != null && ast < 0.3) {
            out.add(new Advice("warn", String.format("助攻只超过 %s 的同英雄玩家，辅助要多跟团、多做视野和控制。", pctText(ast))));
        }
        Double tower = f.pct().get("tower_damage");
        if (core && tower != null && tower < 0.2 && f.durationSec() >= 25 * 60) {
            out.add(new Advice("info", "推塔伤害偏低。打赢团战后记得推进，把优势变成建筑。"));
        }

        // ---------- 死亡 ----------
        double deathsPer10 = f.deaths() / minutes * 10;
        if (deathsPer10 >= 3) {
            out.add(new Advice("bad", String.format("死了 %d 次（平均每 10 分钟 %.1f 次），死亡太多会送钱也会断发育，注意走位和视野。",
                    f.deaths(), deathsPer10)));
        } else if (f.deaths() <= 2 && f.durationSec() >= 25 * 60) {
            out.add(new Advice("good", String.format("全场只死了 %d 次，生存做得很好。", f.deaths())));
        }

        // ---------- 出装 ----------
        if (!f.missingCore().isEmpty()) {
            out.add(new Advice("warn", "高分局这个英雄常出、但你这局没出的装备：" + String.join("、", f.missingCore())
                    + "。可以想想是局势需要换装，还是没想到。"));
        }
        for (LateItem li : f.lateItems()) {
            out.add(new Advice("warn", String.format("%s 在 %s 才出，高分局平均 %s，慢了 %d 分钟。",
                    li.name(), clock(li.yourTime()), clock(li.avgTime()), (li.yourTime() - li.avgTime()) / 60)));
        }
        return out;
    }

    static String pctText(double pct) {
        return Math.round(pct * 100) + "%";
    }

    /** 秒 → "12:05"，负数（开局前买的）→ "-0:30" */
    static String clock(int sec) {
        String sign = sec < 0 ? "-" : "";
        int a = Math.abs(sec);
        return String.format("%s%d:%02d", sign, a / 60, a % 60);
    }
}

package com.dota.guide.review;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/**
 * ItemCatalog —— 物品字典：物品 id / key → 名字、价格、图标
 *
 * 为什么既要 id 又要 key？OpenDota 的数据里两种写法都有：
 *   - 最终出装 item_0..item_5 是数字 id（1 = 闪烁匕首）；
 *   - 购买记录 first_purchase_time / purchase_log 用的是内部名 key（"blink"）。
 * 所以字典两头都要能查。
 *
 * 数据来源：OpenDota 的 /constants/items（英文名、价格、图标），
 * 中文名来自本地 item_cn 表（可选，见 sql/download_items_cn.js），有中文用中文，没有就用英文。
 *
 * 纯数据类，不碰 Spring 和数据库，方便单元测试。
 */
public class ItemCatalog {

    /** 图标地址前缀：OpenDota 给的 img 是 /apps/dota2/... 这样的相对路径 */
    private static final String IMG_HOST = "https://cdn.cloudflare.steamstatic.com";

    public record Item(int id, String key, String name, int cost, String img) {
    }

    public static final ItemCatalog EMPTY = new ItemCatalog(Collections.emptyMap(), Collections.emptyMap());

    private final Map<Integer, Item> byId;
    private final Map<String, Item> byKey;

    private ItemCatalog(Map<Integer, Item> byId, Map<String, Item> byKey) {
        this.byId = byId;
        this.byKey = byKey;
    }

    /**
     * @param constants /constants/items 的返回：{ "blink": { "id": 1, "dname": "Blink Dagger", "cost": 2250, "img": "/apps/..." }, ... }
     * @param cnNames   物品 id → 中文名（可以是空 Map）
     */
    public static ItemCatalog fromOpenDota(JsonNode constants, Map<Integer, String> cnNames) {
        Map<Integer, Item> byId = new HashMap<>();
        Map<String, Item> byKey = new HashMap<>();
        if (constants != null) {
            Iterator<Map.Entry<String, JsonNode>> it = constants.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                String key = e.getKey();
                JsonNode n = e.getValue();
                if (!n.hasNonNull("id") || key.startsWith("recipe_")) {
                    continue;
                }
                int id = n.get("id").asInt();
                String name = cnNames.getOrDefault(id, n.path("dname").asText(key));
                String img = n.path("img").asText("");
                if (!img.isEmpty() && img.startsWith("/")) {
                    img = IMG_HOST + img;
                }
                Item item = new Item(id, key, name, n.path("cost").asInt(0), img);
                byId.put(id, item);
                byKey.put(key, item);
            }
        }
        return new ItemCatalog(byId, byKey);
    }

    /** 查不到时返回 null（比如 0 = 空格子） */
    public Item byId(int id) {
        return byId.get(id);
    }

    public Item byKey(String key) {
        return byKey.get(key);
    }

    public boolean isEmpty() {
        return byId.isEmpty();
    }
}

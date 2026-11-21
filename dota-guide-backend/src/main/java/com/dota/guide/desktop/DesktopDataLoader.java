package com.dota.guide.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * DesktopDataLoader —— 本地软件模式启动时准备数据库（只在 desktop profile 下生效）
 *
 * 每次启动做两件事：
 *   1. 执行 db/desktop-schema.sql 建表（CREATE TABLE IF NOT EXISTS，已有的表不动）；
 *   2. 清空英雄、技能表，重新导入 jar 里自带的 heroes_full.sql / skills_full.sql
 *      （以及有的话 items_cn.sql、hero_style.sql）。
 *
 * 为什么每次都重导？英雄数据是跟着软件版本走的，新版软件带了新数据，
 * 用户一启动就该看到；900 多行导一次不到一秒。
 * 复盘缓存（od_cache）和高分局样本（meta_*）不动，它们是用户自己攒的。
 *
 * 数据文件是给 MySQL 写的，开头有 SET NAMES / USE / TRUNCATE 之类 H2 不认的语句，
 * 所以这里只挑出 INSERT 语句执行。
 */
@Component
@Profile("desktop")
@Order(0)
public class DesktopDataLoader implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DesktopDataLoader.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;

    public DesktopDataLoader(JdbcTemplate jdbc, TransactionTemplate tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        for (String stmt : splitStatements(read("db/desktop-schema.sql"))) {
            jdbc.execute(stmt);
        }
        List<String> heroes = insertsOf(read("db/data/heroes_full.sql"));
        List<String> skills = insertsOf(read("db/data/skills_full.sql"));
        String itemsCn = readOptional("db/data/items_cn.sql");
        String heroStyle = readOptional("db/data/hero_style.sql");
        tx.executeWithoutResult(status -> {
            jdbc.execute("DELETE FROM hero_skill");
            jdbc.execute("DELETE FROM hero");
            heroes.forEach(jdbc::execute);
            skills.forEach(jdbc::execute);
            if (itemsCn != null) {
                jdbc.execute("DELETE FROM item_cn");
                insertsOf(itemsCn).forEach(jdbc::execute);
            }
            if (heroStyle != null) {
                jdbc.execute("DELETE FROM hero_style");
                insertsOf(heroStyle).forEach(jdbc::execute);
            }
        });
        Integer h = jdbc.queryForObject("SELECT COUNT(*) FROM hero", Integer.class);
        Integer s = jdbc.queryForObject("SELECT COUNT(*) FROM hero_skill", Integer.class);
        log.info("本地数据库已就绪：英雄 {} 个，技能 {} 条", h, s);
    }

    /** 从 MySQL 数据文件里挑出 INSERT 语句（一条 INSERT 可能跨很多行，以 ");" 结尾） */
    static List<String> insertsOf(String sql) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = null;
        for (String line : sql.split("\n")) {
            String t = line.strip();
            if (cur == null) {
                if (t.toUpperCase().startsWith("INSERT INTO")) {
                    cur = new StringBuilder();
                } else {
                    continue;
                }
            }
            cur.append(line).append('\n');
            if (t.endsWith(");")) {
                String stmt = cur.toString().strip();
                out.add(stmt.substring(0, stmt.length() - 1));   // 去掉末尾分号
                cur = null;
            }
        }
        return out;
    }

    /** 建表脚本：去掉 -- 注释后按分号切 */
    static List<String> splitStatements(String sql) {
        StringBuilder clean = new StringBuilder();
        for (String line : sql.split("\n")) {
            if (!line.strip().startsWith("--")) {
                clean.append(line).append('\n');
            }
        }
        List<String> out = new ArrayList<>();
        for (String part : clean.toString().split(";")) {
            if (!part.isBlank()) {
                out.add(part.strip());
            }
        }
        return out;
    }

    private static String read(String path) throws IOException {
        ClassPathResource r = new ClassPathResource(path);
        try (BufferedReader br = new BufferedReader(new InputStreamReader(r.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    private static String readOptional(String path) throws IOException {
        return new ClassPathResource(path).exists() ? read(path) : null;
    }
}

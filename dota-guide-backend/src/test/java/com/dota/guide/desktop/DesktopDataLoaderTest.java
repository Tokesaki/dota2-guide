package com.dota.guide.desktop;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 本地软件模式的数据导入测试：用内存版 H2 走一遍完整导入，不需要 MySQL。
 * ./mvnw test -Dtest=DesktopDataLoaderTest
 */
class DesktopDataLoaderTest {

    @Test
    void insertsOfPicksOnlyInsertStatements() {
        String sql = """
                SET NAMES utf8mb4;
                USE dota_guide;
                TRUNCATE TABLE hero;
                INSERT INTO t (a) VALUES
                (1),
                (2);
                -- 注释
                INSERT INTO t (a) VALUES (3);
                """;
        List<String> out = DesktopDataLoader.insertsOf(sql);
        assertEquals(2, out.size());
        assertTrue(out.get(0).startsWith("INSERT INTO t (a) VALUES"));
        assertTrue(out.get(0).endsWith("(2)"), "末尾分号要去掉");
    }

    @Test
    void loadsAllHeroesAndSkillsIntoH2TwiceWithoutDuplicates() throws Exception {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:desktop-test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
                "sa", "");
        JdbcTemplate jdbc = new JdbcTemplate(ds);
        DesktopDataLoader loader = new DesktopDataLoader(jdbc, new TransactionTemplate(new DataSourceTransactionManager(ds)));

        loader.run(null);
        loader.run(null);   // 第二次启动：重导不能重复插入

        assertEquals(127, jdbc.queryForObject("SELECT COUNT(*) FROM hero", Integer.class));
        assertEquals(798, jdbc.queryForObject("SELECT COUNT(*) FROM hero_skill", Integer.class));
        assertEquals("主宰", jdbc.queryForObject("SELECT name FROM hero WHERE slug = 'juggernaut'", String.class));
        assertEquals(127, jdbc.queryForObject("SELECT COUNT(*) FROM hero h JOIN hero_style s ON s.slug = h.slug", Integer.class));

        // 复盘模块用到的 MySQL 写法在 H2 的 MySQL 模式下也要能跑
        jdbc.update("INSERT INTO od_cache (cache_key, body, fetched_at) VALUES ('k', 'a', NOW()) "
                + "ON DUPLICATE KEY UPDATE body = VALUES(body), fetched_at = VALUES(fetched_at)");
        jdbc.update("INSERT INTO od_cache (cache_key, body, fetched_at) VALUES ('k', 'b', NOW()) "
                + "ON DUPLICATE KEY UPDATE body = VALUES(body), fetched_at = VALUES(fetched_at)");
        assertEquals("b", jdbc.queryForObject("SELECT body FROM od_cache WHERE cache_key = 'k'", String.class));
        jdbc.update("INSERT IGNORE INTO meta_scan (match_id, scanned_at) VALUES (1, NOW())");
        jdbc.update("INSERT IGNORE INTO meta_scan (match_id, scanned_at) VALUES (1, NOW())");
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM meta_scan", Integer.class));
    }
}

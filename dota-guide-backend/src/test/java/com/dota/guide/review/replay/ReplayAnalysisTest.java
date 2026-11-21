package com.dota.guide.review.replay;

import com.dota.guide.review.replay.ReplayParser.PlayerInfo;
import com.dota.guide.review.replay.ReplayParser.Raw;
import com.dota.guide.review.replay.ReplayParser.Sample;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ReplayAnalysisTest {

    @Test
    void zonesFollowTheMapLayout() {
        // 天辉视角
        assertEquals("己方基地", zone(-6800, -6200, true));
        assertEquals("敌方高地", zone(6500, 5800, true));
        assertEquals("上路", zone(-6400, 2000, true));
        assertEquals("上路", zone(-1000, 6200, true));
        assertEquals("下路", zone(2000, -6500, true));
        assertEquals("下路", zone(6300, -1000, true));
        assertEquals("中路", zone(500, 300, true));
        assertEquals("河道", zone(-2500, 2600, true));
        assertEquals("己方野区", zone(1000, -3500, true));
        assertEquals("敌方野区", zone(-1000, 3600, true));
        // 夜魇视角：同一个位置，己方/敌方要反过来
        assertEquals("敌方野区", zone(1000, -3500, false));
        assertEquals("己方基地", zone(6500, 5800, false));
    }

    @Test
    void countsDeathsWithNearbyHeroes() {
        List<PlayerInfo> ps = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            ps.add(new PlayerInfo(i + 1, i < 5 ? 2 : 3, null));
        }
        List<Sample> ss = new ArrayList<>();
        // 第 0 秒：0 号（天辉）站在中路，身边 1 个队友、2 个敌人，其余人都在远处
        ss.add(sample(0, new boolean[]{true, true, true, true, true, true, true, true, true, true}));
        // 第 1 秒：0 号死了
        boolean[] dead = {false, true, true, true, true, true, true, true, true, true};
        ss.add(sample(1, dead));
        ReplayAnalysis.Result r = ReplayAnalysis.analyze(1, new Raw(ps, ss), Map.of(1, "axe"));

        ReplayAnalysis.PlayerAnalysis me = r.players().get(0);
        assertEquals("axe", me.slug());
        assertEquals(1, me.deaths().size());
        ReplayAnalysis.Death d = me.deaths().get(0);
        assertEquals(1, d.t());
        assertEquals(1, d.allies());
        assertEquals(2, d.enemies());
        assertEquals("中路", d.zone());
        assertEquals(1, r.trail().size(), "每 2 秒一帧，0 秒那帧");
    }

    @Test
    void detectsCompressionByHeader() throws Exception {
        byte[] plain = "PBDEMS2\0rest".getBytes();
        try (InputStream in = ReplayLocator.decompress(new BufferedInputStream(new ByteArrayInputStream(plain)))) {
            assertArrayEquals(plain, in.readAllBytes());
        }
        ByteArrayOutputStream bz = new ByteArrayOutputStream();
        try (var out = new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bz)) {
            out.write(plain);
        }
        try (InputStream in = ReplayLocator.decompress(new BufferedInputStream(new ByteArrayInputStream(bz.toByteArray())))) {
            assertArrayEquals(plain, in.readAllBytes());
        }
    }

    @Test
    void readsSteamLibraryFolders() {
        String vdf = "\"libraryfolders\"\n{\n\t\"0\"\n\t{\n\t\t\"path\"\t\t\"C:\\\\Program Files (x86)\\\\Steam\"\n\t}\n"
                + "\t\"1\"\n\t{\n\t\t\"path\"\t\t\"D:\\\\SteamLibrary\"\n\t}\n}";
        List<Path> libs = ReplayLocator.parseLibraryFolders(vdf);
        assertEquals(2, libs.size());
        assertTrue(libs.get(1).toString().contains("SteamLibrary"));
    }

    private static String zone(int x, int y, boolean radiant) {
        return ReplayAnalysis.ZONES.get(ReplayAnalysis.zoneOf(x, y, radiant));
    }

    /** 0 号在 (0,0)；1 号队友在 (500,0)；5、6 号敌人在 (0,800)/(800,800)；其余人在很远的地方 */
    private static Sample sample(int t, boolean[] alive) {
        int[] xs = {0, 500, -7000, -7000, -7000, 0, 800, 7000, 7000, 7000};
        int[] ys = {0, 0, -6500, -6500, -6500, 800, 800, 6500, 6500, 6500};
        return new Sample(t, xs, ys, alive);
    }
}

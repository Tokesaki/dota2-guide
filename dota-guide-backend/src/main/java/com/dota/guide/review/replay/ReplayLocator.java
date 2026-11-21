package com.dota.guide.review.replay;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ReplayLocator —— 找到一场比赛的 .dem 录像
 *
 * 两个来源，按顺序试：
 *   1. 本机 Dota 2 的录像文件夹：在游戏客户端里点过"下载录像"的比赛会存在
 *      Steam库/steamapps/common/dota 2 beta/game/dota/replays/比赛编号.dem
 *      Steam 库可能不止一个（装在 D 盘之类），从 Steam 的 libraryfolders.vdf 里读全部库路径。
 *   2. 从 Valve 的录像服务器下载：http://replay{cluster}.valve.net/570/{比赛编号}_{replay_salt}.dem.bz2
 *      cluster 和 replay_salt 在 OpenDota 的比赛详情里有。Valve 一般只保留最近两周左右的录像。
 *      文件名虽然是 .bz2，但现在实际是 zstd 压缩的，所以按文件头判断格式再解压。
 */
public class ReplayLocator {

    private static final Logger log = LoggerFactory.getLogger(ReplayLocator.class);
    private static final String DOTA_REPLAYS = "steamapps/common/dota 2 beta/game/dota/replays";

    private final List<Path> extraDirs;

    public ReplayLocator(List<Path> extraDirs) {
        this.extraDirs = extraDirs;
    }

    /** 在本机录像文件夹里找 比赛编号.dem，找不到返回 null */
    public Path findLocal(long matchId) {
        for (Path dir : replayDirs()) {
            Path f = dir.resolve(matchId + ".dem");
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        return null;
    }

    /** 所有可能放录像的文件夹：配置里额外指定的 + 每个 Steam 库下的 Dota 录像文件夹 */
    public List<Path> replayDirs() {
        Set<Path> dirs = new LinkedHashSet<>(extraDirs);
        for (Path lib : steamLibraries()) {
            dirs.add(lib.resolve(DOTA_REPLAYS));
        }
        return new ArrayList<>(dirs);
    }

    static List<Path> steamLibraries() {
        Set<Path> roots = new LinkedHashSet<>();
        String steamPath = windowsSteamPath();
        if (steamPath != null) {
            roots.add(Path.of(steamPath));
        }
        roots.add(Path.of("C:/Program Files (x86)/Steam"));
        roots.add(Path.of("C:/Program Files/Steam"));
        String home = System.getProperty("user.home", "");
        roots.add(Path.of(home, ".steam/steam"));
        roots.add(Path.of(home, ".local/share/Steam"));
        roots.add(Path.of(home, "Library/Application Support/Steam"));

        Set<Path> libs = new LinkedHashSet<>();
        for (Path root : roots) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            libs.add(root);
            Path vdf = root.resolve("steamapps/libraryfolders.vdf");
            if (Files.isRegularFile(vdf)) {
                try {
                    libs.addAll(parseLibraryFolders(Files.readString(vdf, StandardCharsets.UTF_8)));
                } catch (IOException e) {
                    log.debug("读取 {} 失败：{}", vdf, e.getMessage());
                }
            }
        }
        return new ArrayList<>(libs);
    }

    /** libraryfolders.vdf 里每个库都有一行 "path"  "D:\\SteamLibrary"，反斜杠是转义过的 */
    static List<Path> parseLibraryFolders(String vdf) {
        List<Path> out = new ArrayList<>();
        Matcher m = Pattern.compile("\"path\"\\s+\"([^\"]+)\"").matcher(vdf);
        while (m.find()) {
            out.add(Path.of(m.group(1).replace("\\\\", "\\")));
        }
        return out;
    }

    /** Windows 上从注册表读 Steam 安装位置（HKCU\Software\Valve\Steam 的 SteamPath） */
    private static String windowsSteamPath() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) {
            return null;
        }
        try {
            Process p = new ProcessBuilder("reg", "query", "HKCU\\Software\\Valve\\Steam", "/v", "SteamPath")
                    .redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor();
            Matcher m = Pattern.compile("SteamPath\\s+REG_SZ\\s+(.+)").matcher(out);
            return m.find() ? m.group(1).trim() : null;
        } catch (IOException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 从 Valve 下载录像并解压到 target。
     *
     * @param progress 每下载约 5 MB 回调一次，参数是已下载的 MB 数
     */
    public static void download(String url, Path target, Consumer<Integer> progress) throws IOException {
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(20))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(10)).GET().build();
        HttpResponse<InputStream> resp;
        try {
            resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("下载被中断");
        }
        if (resp.statusCode() == 404) {
            resp.body().close();
            throw new IOException("Valve 服务器上已经没有这场的录像了（一般只保留两周左右）");
        }
        if (resp.statusCode() != 200) {
            resp.body().close();
            throw new IOException("下载录像失败，Valve 服务器返回 " + resp.statusCode());
        }
        Files.createDirectories(target.getParent());
        try (InputStream raw = new CountingStream(resp.body(), progress);
             InputStream in = decompress(new BufferedInputStream(raw, 1 << 16));
             OutputStream out = Files.newOutputStream(target)) {
            in.transferTo(out);
        }
    }

    /** 按文件头判断压缩格式：BZh = bzip2，28 B5 2F FD = zstd，PBDEMS2 = 没压缩 */
    static InputStream decompress(BufferedInputStream in) throws IOException {
        in.mark(8);
        byte[] head = in.readNBytes(4);
        in.reset();
        if (head.length >= 3 && head[0] == 'B' && head[1] == 'Z' && head[2] == 'h') {
            return new BZip2CompressorInputStream(in, true);
        }
        if (head.length == 4 && (head[0] & 0xFF) == 0x28 && (head[1] & 0xFF) == 0xB5
                && (head[2] & 0xFF) == 0x2F && (head[3] & 0xFF) == 0xFD) {
            return new ZstdCompressorInputStream(in);
        }
        return in;
    }

    /** 数着字节往下传，每 5 MB 报一次进度 */
    private static final class CountingStream extends java.io.FilterInputStream {
        private final Consumer<Integer> progress;
        private long count;
        private long nextReport = 5L << 20;

        CountingStream(InputStream in, Consumer<Integer> progress) {
            super(in);
            this.progress = progress;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                if (count >= nextReport) {
                    nextReport += 5L << 20;
                    progress.accept((int) (count >> 20));
                }
            }
            return n;
        }
    }
}

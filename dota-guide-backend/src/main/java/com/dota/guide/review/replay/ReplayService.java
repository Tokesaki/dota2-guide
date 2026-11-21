package com.dota.guide.review.replay;

import com.dota.guide.review.OpenDotaService;
import com.dota.guide.review.ReviewException;
import com.dota.guide.review.ReviewMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * ReplayService —— 录像深度解析的任务管理
 *
 * 解析一场要：找录像（本机 / Valve 下载，下载 70 MB 左右）→ clarity 解析（几秒）→ 分析 → 存缓存。
 * 下载慢，所以做成后台任务：前端点"开始解析"后每隔几秒查一次状态，完成后拿结果。
 * 一次只跑一个任务（单线程），避免同时下好几个大文件。
 *
 * 结果存在 od_cache（键 replay:v2:比赛编号），解析过的比赛下次直接出结果。
 * 从 Valve 下载或用户上传的录像解析完就删掉（一场 100 多 MB）；本机 Dota 文件夹里的录像不动。
 */
@Service
public class ReplayService {

    private static final Logger log = LoggerFactory.getLogger(ReplayService.class);
    private static final String CACHE_PREFIX = "replay:v2:";

    /** state：none 没解析过 / running 进行中 / done 完成 / error 失败 */
    public record Status(String state, String message, ReplayAnalysis.Result result) {
    }

    private final OpenDotaService od;
    private final ReviewMapper mapper;
    private final ObjectMapper json;
    private final ReplayLocator locator;
    private final Path workDir;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "replay-parser");
        t.setDaemon(true);
        return t;
    });
    private final Map<Long, Status> jobs = new ConcurrentHashMap<>();

    public ReplayService(OpenDotaService od, ReviewMapper mapper, ObjectMapper json,
                         @Value("${review.replay.work-dir:${user.home}/.dota2-review/replays}") String workDir,
                         @Value("${review.replay.extra-dirs:}") List<String> extraDirs) {
        this.od = od;
        this.mapper = mapper;
        this.json = json;
        this.workDir = Path.of(workDir);
        List<Path> dirs = new ArrayList<>();
        for (String d : extraDirs) {
            if (d != null && !d.isBlank()) {
                dirs.add(Path.of(d.trim()));
            }
        }
        this.locator = new ReplayLocator(dirs);
    }

    public Status status(long matchId) {
        Status job = jobs.get(matchId);
        if (job != null && !"done".equals(job.state())) {
            return job;
        }
        ReplayAnalysis.Result cached = loadCached(matchId);
        if (cached != null) {
            return new Status("done", "", cached);
        }
        if (locator.findLocal(matchId) != null) {
            return new Status("none", "在你电脑的 Dota 录像文件夹里找到了这场录像，可以直接解析", null);
        }
        return new Status("none", "", null);
    }

    /** 开始解析：先找本机录像，没有就从 Valve 下载 */
    public Status start(long matchId) {
        Status cur = jobs.get(matchId);
        if (cur != null && "running".equals(cur.state())) {
            return cur;
        }
        set(matchId, "running", "排队中…");
        worker.submit(() -> run(matchId, null));
        return jobs.get(matchId);
    }

    /** 用户自己选的 .dem 文件（也接受 Valve 下载的 .bz2 压缩包） */
    public Status upload(long matchId, MultipartFile file) {
        try {
            Files.createDirectories(workDir);
            Path raw = workDir.resolve(matchId + ".upload");
            file.transferTo(raw);
            set(matchId, "running", "排队中…");
            worker.submit(() -> run(matchId, raw));
            return jobs.get(matchId);
        } catch (IOException e) {
            throw new ReviewException(500, "保存上传的录像失败：" + e.getMessage());
        }
    }

    private void run(long matchId, Path uploaded) {
        Path dem = null;
        boolean temporary = true;
        try {
            if (uploaded != null) {
                set(matchId, "running", "正在读取上传的录像…");
                dem = workDir.resolve(matchId + ".dem");
                try (InputStream in = ReplayLocator.decompress(new java.io.BufferedInputStream(Files.newInputStream(uploaded), 1 << 16))) {
                    Files.copy(in, dem, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                Files.deleteIfExists(uploaded);
            } else {
                dem = locator.findLocal(matchId);
                if (dem != null) {
                    temporary = false;
                } else {
                    dem = workDir.resolve(matchId + ".dem");
                    String url = replayUrl(matchId);
                    set(matchId, "running", "正在从 Valve 下载录像…");
                    ReplayLocator.download(url, dem, mb -> set(matchId, "running", "正在从 Valve 下载录像…已下载 " + mb + " MB"));
                }
            }
            set(matchId, "running", "正在解析录像…");
            ReplayParser.Raw raw = ReplayParser.parse(dem);
            if (raw.players().size() < 10 || raw.samples().isEmpty()) {
                throw new IOException("录像里没有读到完整的 10 名玩家，可能不是一场正常的比赛录像");
            }
            ReplayAnalysis.Result result = ReplayAnalysis.analyze(matchId, raw, safeSlugs());
            mapper.saveCache(CACHE_PREFIX + matchId, json.writeValueAsString(result));
            jobs.put(matchId, new Status("done", "", result));
            log.info("录像 {} 解析完成：{} 秒", matchId, result.duration());
        } catch (ReviewException e) {
            set(matchId, "error", e.getMessage());
        } catch (Exception e) {
            log.warn("录像 {} 解析失败", matchId, e);
            set(matchId, "error", e.getMessage() == null ? e.toString() : e.getMessage());
        } finally {
            if (temporary && dem != null) {
                try {
                    Files.deleteIfExists(dem);
                } catch (IOException ignored) {
                    // 删不掉就留着，下次覆盖
                }
            }
        }
    }

    /** OpenDota 比赛详情里的 cluster + replay_salt 拼出 Valve 录像地址 */
    private String replayUrl(long matchId) {
        JsonNode m = od.match(matchId);
        if (m.hasNonNull("replay_url")) {
            return m.get("replay_url").asText();
        }
        if (m.hasNonNull("cluster") && m.hasNonNull("replay_salt")) {
            return "http://replay" + m.get("cluster").asInt() + ".valve.net/570/"
                    + matchId + "_" + m.get("replay_salt").asLong() + ".dem.bz2";
        }
        throw new ReviewException(404, "OpenDota 还不知道这场的录像地址。先点上面的「请求解析」，几分钟后再试；"
                + "或者在 Dota 客户端里下载这场录像后再点解析，也可以手动选择录像文件");
    }

    private Map<Integer, String> safeSlugs() {
        try {
            return od.heroSlugs();
        } catch (ReviewException e) {
            return Map.of();
        }
    }

    private ReplayAnalysis.Result loadCached(long matchId) {
        ReviewMapper.CacheRow row = mapper.findCache(CACHE_PREFIX + matchId);
        if (row == null) {
            return null;
        }
        try {
            return json.readValue(row.body, ReplayAnalysis.Result.class);
        } catch (JsonProcessingException e) {
            log.warn("录像 {} 的缓存读不出来，当作没解析过：{}", matchId, e.getMessage());
            return null;
        }
    }

    private void set(long matchId, String state, String message) {
        jobs.put(matchId, new Status(state, message, null));
    }
}

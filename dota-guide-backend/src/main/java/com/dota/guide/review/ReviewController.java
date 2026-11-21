package com.dota.guide.review;

import com.dota.guide.common.Result;
import com.dota.guide.review.replay.ReplayService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * ReviewController —— 比赛复盘的 4 个接口
 *
 *   GET  /api/review/players/{accountId}        玩家资料 + 最近 20 场
 *   GET  /api/review/matches/{matchId}          单场复盘（10 个人的数据、出装对比、建议）
 *   POST /api/review/matches/{matchId}/parse    请求 OpenDota 解析这场录像
 *   GET  /api/review/heroes/{key}/builds        英雄的高分局出装（key 是 slug 或数字 id）
 *   GET  /api/review/matches/{matchId}/replay   录像深度解析的状态 / 结果
 *   POST /api/review/matches/{matchId}/replay   开始解析（先找本机录像，没有就从 Valve 下载）
 *   POST /api/review/matches/{matchId}/replay/upload  上传 .dem 录像文件后解析
 *
 * accountId 就是 Steam 好友代码（游戏里个人资料页能看到的那串数字）。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewService reviewService;
    private final ReplayService replayService;

    public ReviewController(ReviewService reviewService, ReplayService replayService) {
        this.reviewService = reviewService;
        this.replayService = replayService;
    }

    @GetMapping("/matches/{matchId}/replay")
    public Result<ReplayService.Status> replayStatus(@PathVariable long matchId) {
        return Result.success(replayService.status(matchId));
    }

    @PostMapping("/matches/{matchId}/replay")
    public Result<ReplayService.Status> replayStart(@PathVariable long matchId) {
        return Result.success(replayService.start(matchId));
    }

    @PostMapping("/matches/{matchId}/replay/upload")
    public Result<ReplayService.Status> replayUpload(@PathVariable long matchId, @RequestParam("file") MultipartFile file) {
        return Result.success(replayService.upload(matchId, file));
    }

    @GetMapping("/players/{accountId}")
    public Result<ReviewDtos.PlayerProfile> player(@PathVariable long accountId) {
        return Result.success(reviewService.profile(accountId));
    }

    @GetMapping("/matches/{matchId}")
    public Result<ReviewDtos.MatchReview> match(@PathVariable long matchId) {
        return Result.success(reviewService.review(matchId));
    }

    @PostMapping("/matches/{matchId}/parse")
    public Result<Boolean> parse(@PathVariable long matchId) {
        return Result.success(reviewService.requestParse(matchId));
    }

    @GetMapping("/heroes/{key}/builds")
    public Result<ReviewDtos.HeroBuilds> heroBuilds(@PathVariable String key) {
        return Result.success(reviewService.heroBuilds(key));
    }
}

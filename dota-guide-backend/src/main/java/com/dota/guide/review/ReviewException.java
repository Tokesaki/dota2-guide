package com.dota.guide.review;

/**
 * 复盘模块的业务异常：带一个 code，由 GlobalExceptionHandler 原样转成 { code, message }。
 *   404 —— 比赛 / 玩家 / 英雄不存在
 *   429 —— OpenDota 免费额度用完
 *   502 —— OpenDota 出错或连不上
 */
public class ReviewException extends RuntimeException {

    private final int code;

    public ReviewException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}

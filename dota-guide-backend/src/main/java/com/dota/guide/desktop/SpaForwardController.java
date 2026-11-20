package com.dota.guide.desktop;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * 本地软件模式下，前端页面由后端直接托管（打包时把 vite build 的产物放进 classpath:/static）。
 *
 * 前端用的是 History 路由（/heroes/sven、/review/123 这种没有 # 的地址），
 * 用户在这些地址上刷新页面时，浏览器会直接向后端要 /review/123 —— 后端没有这个文件，就会 404。
 * 这里把前端的几个页面路径都转给 index.html，让前端路由自己处理。
 * /api/** 不受影响（这里没有匹配 /api 开头的路径）。
 */
@Controller
@Profile("desktop")
public class SpaForwardController {

    @GetMapping({"/heroes", "/heroes/{key}", "/advanced", "/review", "/review/{matchId}"})
    public String forward() {
        return "forward:/index.html";
    }
}

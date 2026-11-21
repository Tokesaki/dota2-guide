package com.dota.guide.review;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * OpenDotaClient —— 只负责"发 HTTP 请求给 OpenDota，拿回 JSON 字符串"
 *
 * 不做缓存、不解析 JSON（那是 OpenDotaService 的事），只管三件事：
 *   1. 拼地址（有 api-key 就带上）；
 *   2. 限速：两次请求之间至少隔 min-interval-ms，免费额度每分钟 60 次，默认 1.1 秒一次刚好不超；
 *   3. 把各种失败统一翻译成 ReviewException（404 / 429 / 502）。
 *
 * 配置见 application.yml 的 review.opendota.*。
 */
@Component
public class OpenDotaClient {

    private final RestClient http;
    private final String apiKey;
    private final long minIntervalMs;
    private long lastCallAt = 0;

    public OpenDotaClient(@Value("${review.opendota.base-url:https://api.opendota.com/api}") String baseUrl,
                          @Value("${review.opendota.api-key:}") String apiKey,
                          @Value("${review.opendota.min-interval-ms:1100}") long minIntervalMs) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        this.http = RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.minIntervalMs = minIntervalMs;
    }

    public String get(String path) {
        return call("GET", path);
    }

    public String post(String path) {
        return call("POST", path);
    }

    private String call(String method, String path) {
        throttle();
        String uri = withKey(path);
        try {
            String body = http.method(HttpMethod.valueOf(method)).uri(uri).retrieve().body(String.class);
            return body == null ? "" : body;
        } catch (RestClientResponseException e) {
            HttpStatusCode s = e.getStatusCode();
            if (s.value() == 404) {
                throw new ReviewException(404, "OpenDota 上找不到这条数据");
            }
            if (s.value() == 429) {
                throw new ReviewException(429, "OpenDota 免费额度用完了，请稍后再试");
            }
            throw new ReviewException(502, "OpenDota 返回错误 " + s.value());
        } catch (RestClientException e) {
            throw new ReviewException(502, "连不上 OpenDota：" + e.getMessage());
        }
    }

    private String withKey(String path) {
        if (apiKey.isEmpty()) {
            return path;
        }
        return path + (path.contains("?") ? "&" : "?") + "api_key=" + apiKey;
    }

    /** 简单限速：同一时刻只有一个请求在发，且与上一个间隔 minIntervalMs */
    private synchronized void throttle() {
        long wait = lastCallAt + minIntervalMs - System.currentTimeMillis();
        if (wait > 0) {
            try {
                Thread.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        lastCallAt = System.currentTimeMillis();
    }
}

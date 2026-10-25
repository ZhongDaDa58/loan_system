package com.loan.cs;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * AI 客服服务（Python）的连接配置。
 *
 * <p>对应 {@code application.properties} 里的 {@code cs.ai.*}。
 * 服务地址与 token 都走环境变量，避免把凭证写进仓库。
 */
@Data
@Component
@ConfigurationProperties(prefix = "cs.ai")
public class CsAiProperties {

    /** Python AI 服务地址 */
    private String baseUrl = "http://127.0.0.1:8000";

    /**
     * 服务间调用令牌。
     *
     * <p>Java → Python 时放在请求头里没有意义（Python 信任内网），
     * 真正起作用的是 <b>Python → Java 的 /internal/cs/** 方向</b>：
     * {@link com.loan.config.CsInternalAuthInterceptor} 用它验证调用方。
     * 两边必须配置成同一个值。
     */
    private String serviceToken = "dev-token-change-me";

    /** 连接超时（毫秒） */
    private int connectTimeoutMs = 3000;

    /** 读超时（毫秒）。流式场景下这是"两个 token 之间"的最长间隔 */
    private int readTimeoutMs = 20000;
}

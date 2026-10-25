package com.loan.config;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.PrintWriter;

/**
 * 校验"服务间调用"身份：Python AI 服务 → Java 的 {@code /internal/cs/**}。
 *
 * <p><b>为什么需要它</b>：{@code /internal/cs/**} 在 {@code SecurityConfig} 里是 permitAll
 * （否则 Python 无法带 JWT 调进来），所以必须由本拦截器补上准入校验，否则任何能访问
 * 8080 的人都能伪造 {@code X-CS-User-Id} 读别人的账单。
 *
 * <p><b>三个校验</b>：
 * <ol>
 *   <li>共享令牌 {@code X-CS-Service-Token} 必须匹配配置值</li>
 *   <li>{@code X-CS-User-Id} 必须非空</li>
 *   <li>把 userId 写入 request attribute，让内部接口能用与现有 Controller 一致的写法</li>
 * </ol>
 *
 * <p><b>已知局限</b>：共享令牌是静态密钥，无法防重放。生产环境应换成
 * mTLS 或短期签名（HMAC + 时间戳）。学习项目用共享令牌足够，但要知道这条边界。
 */
public class CsInternalAuthInterceptor implements HandlerInterceptor {

    private static final Logger log = LoggerFactory.getLogger(CsInternalAuthInterceptor.class);

    private final String expectedToken;

    public CsInternalAuthInterceptor(String expectedToken) {
        this.expectedToken = expectedToken;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {

        String token = request.getHeader("X-CS-Service-Token");
        if (token == null || !constantTimeEquals(expectedToken, token)) {
            log.warn("内部接口令牌校验失败: path={}, remote={}", request.getRequestURI(), request.getRemoteAddr());
            writeJson(response, 403, "内部接口令牌无效");
            return false;
        }

        String userId = request.getHeader("X-CS-User-Id");
        if (userId == null || userId.isBlank()) {
            writeJson(response, 400, "缺少用户标识");
            return false;
        }

        request.setAttribute("userId", userId.trim());
        return true;
    }

    private void writeJson(HttpServletResponse response, int code, String msg) throws Exception {
        response.setStatus(code);
        response.setContentType("application/json;charset=utf-8");
        try (PrintWriter out = response.getWriter()) {
            out.write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}");
            out.flush();
        }
    }

    /** 避免令牌比较的时序侧信道。 */
    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int diff = 0;
        for (int i = 0; i < a.length(); i++) {
            diff |= a.charAt(i) ^ b.charAt(i);
        }
        return diff == 0;
    }
}

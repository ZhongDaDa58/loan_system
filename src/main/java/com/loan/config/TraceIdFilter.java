package com.loan.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;

/**
 * 请求级 traceId 注入（阶段一 1.3）
 *
 * <p><b>作用</b>：为每个请求生成/透传一个 traceId 写入 {@link MDC}，
 * 由 logback pattern 中的 {@code %X{traceId}} 输出，使同一请求的所有日志可被一条命令grep出来。
 *
 * <p><b>约定</b>：
 * <ul>
 *   <li>请求头带 {@code X-Trace-Id} 则复用（便于前端/网关串联），否则生成 16 位随机串
 *   <li>响应头回写 {@code X-Trace-Id}，便于调用方定位问题
 *   <li><b>finally 中必须 {@link MDC#clear()}</b> —— Tomcat 线程池会复用线程，
 *       不清理会把上一个请求的 traceId 带到下一个请求（日志串号）
 * </ul>
 *
 * <p><b>已知限制</b>：{@code @Async} 方法运行在新线程上，<b>不会继承 MDC</b>。
 * 需要在阶段二引入线程池时配合 {@code TaskDecorator} 复制 MDC（见开发文档 §4）。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    /** MDC key：traceId（与 logback-spring.xml 的 %X{traceId} 对应） */
    public static final String TRACE_ID = "traceId";

    /** MDC key：applicationId（在业务层生成申请单号后写入） */
    public static final String APP_ID = "applicationId";

    private static final String HEADER = "X-Trace-Id";

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = request.getHeader(HEADER);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        }
        MDC.put(TRACE_ID, traceId);
        response.setHeader(HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.clear();   // ★ 必须清，否则线程池复用导致 traceId 串号
            // 2.2a：请求级缓存同样必须清理 —— 线程复用会把上个请求的用户数据带到下个请求，
            // 在风控场景这是"数据串号"，比性能问题严重得多。
            com.loan.util.RequestCache.clear();
        }
    }
}

package com.loan.config;

import com.loan.util.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * JWT 认证过滤器（阶段四 4.1）
 *
 * <p><b>替代 {@code LoginInterceptor}</b>：原来那个拦截器只做"是否登录"的判断，
 * 而且把 role 读出来后只 setAttribute 从不使用。本过滤器把它升级为
 * <b>真正往 SecurityContext 里放 Authentication</b>，后续的 URL 规则鉴权和
 * {@code @PreAuthorize} 才能生效。
 *
 * <p><b>兼容性</b>：仍然保留 {@code request.setAttribute("userId"/"role")} ——
 * 老代码里有大量 {@code (String) request.getAttribute("userId")}，不能一刀切掉。
 *
 * <p><b>为什么不在这里抛 401</b>：token 缺失/无效时只是"不设置认证"，
 * 继续走 FilterChain，由 {@code SecurityFilterChain} 的
 * {@code authenticationEntryPoint} 统一返回 JSON 401。
 * 这样公开接口（登录、注册）即使不带 token 也能正常访问。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtAuthenticationFilter.class);

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private DbUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");

        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            String userId = jwtUtil.getUserIdFromToken(token);
            if (userId != null) {
                try {
                    UserDetails user = userDetailsService.loadUserByUsername(userId);
                    UsernamePasswordAuthenticationToken auth =
                            new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
                    auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(auth);

                    // 兼容老代码：仍然暴露这两个 request attribute
                    request.setAttribute("userId", userId);
                    request.setAttribute("role", jwtUtil.getRoleFromToken(token));
                } catch (Exception e) {
                    // token 有效但用户被禁用/删除 → 当作未认证处理（401），不中断链路
                    log.warn("JWT 认证失败（用户不可用）: userId={}, reason={}", userId, e.getMessage());
                    SecurityContextHolder.clearContext();
                }
            }
        }

        try {
            chain.doFilter(request, response);
        } finally {
            // Tomcat 线程池复用，必须清 —— 否则上个请求的身份可能带给下个请求
            SecurityContextHolder.clearContext();
        }
    }
}

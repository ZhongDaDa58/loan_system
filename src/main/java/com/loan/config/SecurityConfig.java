package com.loan.config;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import java.io.IOException;

/**
 * Spring Security 配置（阶段四 4.1）
 *
 * <p><b>改造前的问题</b>：
 * <pre>
 * &#64;EnableMethodSecurity             ← 打开了方法级安全，但一个 &#64;PreAuthorize 都没用
 * .anyRequest().permitAll()         ← 所有请求放行，Security 既不认证也不授权
 * </pre>
 * 真正的鉴权靠手写 {@code LoginInterceptor}，而它只校验"是否登录"，
 * <b>role 读出来了却从不校验</b> → 普通用户 token 能调管理端接口（P-022）。
 *
 * <p><b>改造后</b>：
 * <ul>
 *   <li>{@link JwtAuthenticationFilter} 解析 token 并放入 SecurityContext</li>
 *   <li>本类按 URL 做授权：管理端必须 ADMIN，其余必须登录</li>
 *   <li>{@code authenticationEntryPoint / accessDeniedHandler} 统一返回 JSON，
 *       而不是 Spring 默认的 302 跳转或 HTML 错误页（App 端只认 JSON）</li>
 * </ul>
 *
 * <p><b>⚠️ 同时必须移除 {@code LoginInterceptor} 的注册</b>（见 {@code WebMvcConfig}）——
 * 两套鉴权并存会导致错误信息不一致，排查困难。
 */
@Configuration
@EnableMethodSecurity
public class SecurityConfig {

    /** 完全公开的路径：登录/注册/产品浏览/Swagger/合同静态文件 */
    private static final String[] PUBLIC_PATHS = {
            // 用户与管理员登录注册
            "/api/v1/user/register",
            "/api/v1/user/login",
            "/api/v1/admin/register",
            "/api/v1/admin/login",
            // 产品（申请页需要浏览）
            "/api/v1/products",
            "/api/v1/products/**",
            // 接口文档
            "/swagger-ui.html",
            "/swagger-ui/**",
            "/v3/api-docs/**",
            // 合同 PDF 静态访问
            "/contracts/**",
            // AI 客服：Python 服务 → Java 的内部只读接口。
            // ⚠️ 这里必须 permitAll，因为 Python 带不了用户 JWT；
            //    真正的准入校验由 CsInternalAuthInterceptor 用共享令牌完成，
            //    漏了那个拦截器就等于把所有人的账单暴露给任何能访问 8080 的人。
            "/internal/cs/**"
    };

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtAuthenticationFilter jwtFilter) throws Exception {
        http
                // 前后端分离 + JWT，不需要 CSRF
                .csrf(csrf -> csrf.disable())
                // 无状态：不创建 HttpSession
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // JWT 过滤器要在用户名密码认证过滤器之前
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class)

                .authorizeHttpRequests(auth -> auth
                        // 1) 公开
                        .requestMatchers(PUBLIC_PATHS).permitAll()
                        // 2) actuator：健康检查公开（容器 healthcheck / 编排探针要用），其余仅 ADMIN
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/actuator/**").hasRole("ADMIN")
                        // 3) 管理端接口：仅 ADMIN
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        // 4) 风控规则配置：仅 ADMIN（普通用户不能读改风控规则）
                        .requestMatchers("/api/v1/risk/rules", "/api/v1/risk/rules/**").hasRole("ADMIN")
                        // 4.1) 风控决策日志与规则命中记录：仅 ADMIN
                        //      ⚠️ 之前遗漏：/api/v1/risk/log/** 落到了 anyRequest().authenticated()，
                        //      普通用户能读到任意申请的评分决策明细（含收入、负债率等敏感特征）
                        .requestMatchers("/api/v1/risk/log", "/api/v1/risk/log/**").hasRole("ADMIN")
                        // 5) 审核与放款：仅 ADMIN
                        .requestMatchers("/api/v1/loan/audit/**").hasRole("ADMIN")
                        // 5.1) 评分卡 v2 详情：仅 ADMIN（含全量特征与 A/B/C 档归因，属审核端信息）
                        .requestMatchers("/api/v1/scorecard/v2/**").hasRole("ADMIN")
                        // 6) 其余全部需要登录
                        .anyRequest().authenticated()
                )

                // 未认证 / 无权限 → 返回 JSON（App 与前端只解析 {code,msg,data}）
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) ->
                                writeJson(res, HttpServletResponse.SC_UNAUTHORIZED, "未登录或令牌失效"))
                        .accessDeniedHandler((req, res, ex) ->
                                writeJson(res, HttpServletResponse.SC_FORBIDDEN, "无权访问该资源"))
                );

        return http.build();
    }

    private void writeJson(HttpServletResponse res, int code, String msg) throws IOException {
        res.setStatus(code);
        res.setContentType("application/json;charset=utf-8");
        res.getWriter().write("{\"code\":" + code + ",\"msg\":\"" + msg + "\",\"data\":null}");
    }
}

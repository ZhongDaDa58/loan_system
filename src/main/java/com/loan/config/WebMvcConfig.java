package com.loan.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * MVC 配置（阶段四 4.1 改造后）
 *
 * <p><b>已移除 {@code LoginInterceptor} 的注册</b>。
 *
 * <p>原因：项目的鉴权在阶段四 4.1 已交给 Spring Security
 * （{@link JwtAuthenticationFilter} + {@link SecurityConfig}）。
 * 如果保留这个拦截器，会形成**两套鉴权并存**：
 * <ul>
 *   <li>Security 已拒绝的请求，被拦截器再用不同的 JSON 格式拒绝一次 → 错误信息不一致</li>
 *   <li>拦截器放行但 Security 拒绝时，排查者容易只看拦截器日志而误判</li>
 * </ul>
 *
 * <p>{@code LoginInterceptor} 类本身保留（不删文件），只是不再注册 —— 需要回滚时
 * 恢复 {@code addInterceptors} 即可。
 *
 * <p>它原来承担的 {@code request.setAttribute("userId")} 兼容职责，
 * 已由 {@link JwtAuthenticationFilter} 继续提供，老代码不受影响。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    @Value("${contract.storage.path:D:/code/loan/contracts/}")
    private String contractStoragePath;

    // ===== 阶段四 4.1：鉴权交给 Spring Security，此处不再注册 LoginInterceptor =====
    // 如需回滚，恢复下面这段（并同时把 SecurityConfig 的 anyRequest() 改回 permitAll）：
    //
    // @Bean
    // public LoginInterceptor loginInterceptor() { return new LoginInterceptor(); }
    //
    // @Override
    // public void addInterceptors(InterceptorRegistry registry) {
    //     registry.addInterceptor(loginInterceptor())
    //             .addPathPatterns("/api/v1/**")
    //             .excludePathPatterns("/api/v1/user/register", "/api/v1/admin/register",
    //                     "/api/v1/admin/login", "/api/v1/user/login",
    //                     "/api/v1/products", "/api/v1/file/**");
    // }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /contracts/** 的请求映射到本地文件系统
        registry.addResourceHandler("/contracts/**")
                .addResourceLocations("file:" + contractStoragePath);
    }
}

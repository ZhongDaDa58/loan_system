package com.loan.config;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class CorsConfig implements WebMvcConfigurer {
    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/**")  // 所有接口允许跨域
                .allowedOrigins("*")  // 开发环境允许所有来源（生产环境指定前端域名）
                .allowedMethods("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")  // 允许的请求方法（PATCH：规则启用/停用、影子开关）
                .allowedHeaders("*")  // 允许的请求头
                .exposedHeaders("Authorization")  // 允许前端获取的响应头
                .maxAge(0);  // 预检缓存时间（0：开发环境不缓存，避免 CORS 配置变更后浏览器仍复用旧预检结果）
    }
}
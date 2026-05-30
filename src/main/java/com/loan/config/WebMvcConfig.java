package com.loan.config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry; // ⭐ 确保导入这个包
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebMvcConfig implements WebMvcConfigurer {
    @Value("${contract.storage.path:./contracts/}")
    private String contractStoragePath;

    @Bean
    public LoginInterceptor loginInterceptor() {
        return new LoginInterceptor();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(loginInterceptor())
                .addPathPatterns("/api/v1/**")
                .excludePathPatterns("/api/v1/user/register",
                        "/api/v1/admin/register",
                        "/api/v1/admin/login",
                        "/api/v1/user/login",
                        "/api/v1/products");

    }
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        // 将 /contracts/** 的请求映射到本地文件系统
        registry.addResourceHandler("/contracts/**")
                .addResourceLocations("file:" + contractStoragePath);
    }
}
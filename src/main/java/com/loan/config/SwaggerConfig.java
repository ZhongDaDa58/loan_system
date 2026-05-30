package com.loan.config;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerConfig {

    /**
     * 配置 Swagger 基础信息（标题、描述、版本等）
     */
    @Bean
    public OpenAPI customOpenAPI() {
        return new OpenAPI()
                // 项目基础信息
                .info(new Info()
                        .title("你的项目名称 API 文档") // 文档标题
                        .description("基于 Spring Boot + Swagger3.0 的接口文档（仅覆盖 controller 包）") // 文档描述
                        .version("1.0.0") // 接口版本
                        .contact(new io.swagger.v3.oas.models.info.Contact() // 联系人信息（可选）
                                .name("开发者")
                                .email("xxx@xxx.com")));
    }

}
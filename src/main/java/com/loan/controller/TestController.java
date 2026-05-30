package com.loan.controller;
import com.loan.entity.vo.Result;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1")
@Tag(name = "测试接口", description = "验证项目骨架可用性")
public class TestController {

    @GetMapping("/hello")
    @Operation(summary = "基础测试", description = "返回成功信息")
    public Result<String> hello() {
        return Result.success("Spring Boot 4.0 + Java 17 项目启动成功！");
    }
}
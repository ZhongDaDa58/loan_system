package com.loan.controller;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import com.loan.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;


@RestController
@RequestMapping("/api/v1/user")
@Tag(name = "用户模块", description = "普通用户注册、登录 + 审批者登录")
public class SysUserController {

    @Resource
    private SysUserService sysUserService;

    @PostMapping("/register")
    @Operation(summary = "普通用户注册", description = "输入手机号、密码、真实姓名注册")
    public Result<?> register(@Valid @RequestBody UserRegisterDTO registerDTO) {
        return sysUserService.register(registerDTO);
    }

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "普通用户和审批者通用登录接口")
    public Result<?> login(@Valid @RequestBody UserLoginDTO loginDTO) {
        return sysUserService.login(loginDTO);
    }
}
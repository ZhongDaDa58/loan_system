package com.loan.controller;
import com.loan.entity.dto.UserChangePasswordDTO;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import com.loan.service.SysUserService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;


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

    /** 阶段四 4.4：登录限流（防暴力破解） */
    @Resource
    private com.loan.service.RateLimitService rateLimitService;

    @PostMapping("/login")
    @Operation(summary = "用户登录", description = "普通用户和审批者通用登录接口")
    public Result<?> login(@Valid @RequestBody UserLoginDTO loginDTO) {
        // 阶段四 4.4：同一手机号 5 分钟内最多 5 次登录尝试
        rateLimitService.checkLogin(loginDTO.getPhone());
        return sysUserService.login(loginDTO);
    }

    @PutMapping("/password")
    @Operation(summary = "修改密码", description = "登录用户修改自己的密码，需校验原密码")
    public Result<?> changePassword(@Valid @RequestBody UserChangePasswordDTO dto,
                                    HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return sysUserService.changePassword(userId, dto);
    }
}
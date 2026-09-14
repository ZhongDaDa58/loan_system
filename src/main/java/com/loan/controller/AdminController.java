
package com.loan.controller;

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

@Tag(name = "管理员模块", description = "审核员登录接口")
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {

    @Resource
    private SysUserService sysUserService;

    @PostMapping("/login")
    @Operation(summary = "Web 管理端登录", description = "仅审核员可登录（role=admin），普通用户无法登录")
    public Result<?> adminLogin(@Valid @RequestBody UserLoginDTO loginDTO) {
        return sysUserService.adminLogin(loginDTO);
    }
    @PostMapping("/register")
    @Operation(summary = "创建审核员账号", description = "仅用于初始化审核员账号，生产环境应删除")
    public Result<?> registerAdmin(@Valid @RequestBody UserRegisterDTO registerDTO) {
        return sysUserService.registerAdmin(registerDTO);
    }

    @PutMapping("/users/{userId}/toggle-status")
    @Operation(summary = "切换用户账号状态", description = "禁用/启用用户账号，不能对自己操作")
    public Result<?> toggleUserStatus(@PathVariable String userId, HttpServletRequest request) {
        String operatorId = (String) request.getAttribute("userId");
        return sysUserService.toggleUserStatus(userId, operatorId);
    }
}

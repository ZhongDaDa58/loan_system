package com.loan.controller;

import com.loan.entity.dto.CreditProfileDTO;
import com.loan.entity.vo.Result;
import com.loan.service.UserCreditProfileService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/credit-profile")
@Tag(name = "信用档案模块", description = "用户信用档案管理，建档后申请贷款无需重复填写个人信息")
public class UserCreditProfileController {

    @Resource
    private UserCreditProfileService creditProfileService;

    @PostMapping
    @Operation(summary = "创建信用档案", description = "首次填写个人信息、家庭、财务、职业信息并自动评分")
    public Result<?> createProfile(@Valid @RequestBody CreditProfileDTO dto,
                                   HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditProfileService.createProfile(userId, dto);
    }

    @PutMapping
    @Operation(summary = "更新信用档案", description = "更新个人信息后自动重新评分")
    public Result<?> updateProfile(@Valid @RequestBody CreditProfileDTO dto,
                                   HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditProfileService.updateProfile(userId, dto);
    }

    @GetMapping
    @Operation(summary = "查询信用档案", description = "查看当前信用档案和评分结果")
    public Result<?> getProfile(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditProfileService.getProfile(userId);
    }

    @DeleteMapping
    @Operation(summary = "删除信用档案", description = "删除当前用户的信用档案")
    public Result<?> deleteProfile(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditProfileService.deleteProfile(userId);
    }

    @PostMapping("/rescore")
    @Operation(summary = "重新评分", description = "基于现有档案数据重新计算信用评分")
    public Result<?> rescore(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditProfileService.rescore(userId);
    }
}

package com.loan.controller;

import com.loan.entity.dto.LoanApplyDTO;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.Result;
import com.loan.service.LoanApplicationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;


import java.util.List;

@RestController
@RequestMapping("/api/v1/loan/application")
@Tag(name = "贷款申请模块", description = "提交贷款申请、查询申请进度")
public class LoanApplicationController {

    @Resource
    private LoanApplicationService loanApplicationService;

    /**
     * 提交贷款申请（需登录）
     */
    @PostMapping("/submit")
    @Operation(summary = "提交贷款申请", description = "校验产品、风控预审后保存申请记录")
    public Result<?> submitApply(@Valid @RequestBody LoanApplyDTO applyDTO, HttpServletRequest request) {
        // 从请求属性中获取登录用户ID（拦截器已存入）
        String userId = (String) request.getAttribute("userId");
        return loanApplicationService.submitApply(applyDTO, userId);
    }

    /**
     * 通过基础信息提交贷款申请（需登录）
     */
    @PostMapping("/submit-with-basic-info")
    @Operation(summary = "通过基础信息提交贷款申请", description = "接收用户完整基础信息，自动转换特征并进行评分决策")
    public Result<?> submitApplyWithBasicInfo(
            @Valid @RequestBody UserBasicInfoDTO basicInfo,
            HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return loanApplicationService.submitApplyWithBasicInfo(basicInfo, userId);
    }

    /**
     * 查询申请进度（需登录）
     */
    @GetMapping("/progress")
    @Operation(summary = "查询申请进度", description = "根据当前登录用户ID查询所有申请记录")
    public Result<List<LoanApplicationVO>> queryProgress(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return loanApplicationService.queryApplyProgress(userId);
    }
}
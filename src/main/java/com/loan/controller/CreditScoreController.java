package com.loan.controller;

import com.loan.entity.dto.CreditScoreUpdateDTO;
import com.loan.entity.vo.Result;
import com.loan.service.CreditScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/credit-score")
@Tag(name = "信用分管理模块", description = "用户信用分查询和设置（开发测试用）")
public class CreditScoreController {

    @Resource
    private CreditScoreService creditScoreService;

    @GetMapping
    @Operation(summary = "查询当前用户信用分", description = "根据JWT令牌获取当前登录用户的信用分")
    public Result<?> getCreditScore(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return creditScoreService.getCreditScore(userId);
    }

    @PostMapping
    @Operation(summary = "设置信用分（开发测试用）", description = "用户可以手动设置自己的信用分，范围300-950")
    public Result<?> updateCreditScore(
            @Valid @RequestBody CreditScoreUpdateDTO dto,
            HttpServletRequest request
    ) {
        String userId = (String) request.getAttribute("userId");
        return creditScoreService.updateCreditScore(userId, dto.getCreditScore());
    }
}

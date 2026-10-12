package com.loan.controller;

import com.loan.entity.dto.RiskDecisionRequest;
import com.loan.entity.vo.Result;
import com.loan.service.RiskDecisionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 统一风险决策接口（阶段 2 · 决策编排）
 *
 * 一次调用完成：L1 规则（前置拦截）→ 特征装配 → 评分 → 产品阈值基准决策 → L3 规则（后置修正）→ 统一留痕。
 * 说明：本接口为**独立入口**，不替换既有申请链路（避免回归风险）。
 */
@RestController
@RequestMapping("/api/v1/risk")
@Tag(name = "统一决策模块", description = "L1 规则 → 评分 → L3 规则 → 决策 + 留痕（阶段 2）")
public class RiskDecisionController {

    @Resource
    private RiskDecisionService riskDecisionService;

    @PostMapping("/decision")
    @Operation(summary = "统一风险决策",
            description = "请求体 {\"userId\":\"...\",\"applicationId\":\"...\",\"productId\":\"product_001\",\"amount\":50000}")
    public Result<?> decide(@Valid @RequestBody RiskDecisionRequest request) {
        return Result.success(riskDecisionService.decide(request));
    }
}

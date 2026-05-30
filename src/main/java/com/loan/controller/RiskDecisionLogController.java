package com.loan.controller;

import com.loan.entity.RiskDecisionLog;
import com.loan.entity.vo.Result;
import com.loan.service.RiskDecisionLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/risk/log")
@Tag(name = "风控日志模块", description = "查询风控决策日志")
public class RiskDecisionLogController {

    @Resource
    private RiskDecisionLogService riskDecisionLogService;

    @GetMapping("/application/{applicationId}")
    @Operation(summary = "根据申请ID查询风控日志", description = "查询指定申请的风控决策详情")
    public Result<RiskDecisionLog> getByApplicationId(@PathVariable String applicationId) {
        RiskDecisionLog log = riskDecisionLogService.getByApplicationId(applicationId);
        if (log == null) {
            return Result.error(404, "未找到该申请的风控日志");
        }
        return Result.success(log);
    }

    @GetMapping("/user/{userId}")
    @Operation(summary = "根据用户ID查询风控日志", description = "查询用户的所有风控决策历史")
    public Result<List<RiskDecisionLog>> getByUserId(@PathVariable String userId) {
        List<RiskDecisionLog> logs = riskDecisionLogService.getByUserId(userId);
        return Result.success(logs);
    }

    @GetMapping("/recent")
    @Operation(summary = "查询最近的风控日志", description = "查询最近N条风控决策记录")
    public Result<List<RiskDecisionLog>> getRecentLogs(
            @RequestParam(defaultValue = "50") int limit) {
        List<RiskDecisionLog> logs = riskDecisionLogService.getRecentLogs(limit);
        return Result.success(logs);
    }
}

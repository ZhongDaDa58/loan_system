package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.DisbursementStatsVO;
import com.loan.service.DisbursementStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan/disbursement")
@Tag(name = "放款统计模块", description = "放款数据统计：核心指标、趋势、产品占比、金额/期限分布")
public class DisbursementStatsController {

    @Resource
    private DisbursementStatsService disbursementStatsService;

    @GetMapping("/stats")
    @Operation(summary = "获取放款统计数据", description = "返回核心指标、月度趋势、产品占比、金额分布和期限分布")
    public Result<DisbursementStatsVO> getStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return disbursementStatsService.getStats(startDate, endDate);
    }
}

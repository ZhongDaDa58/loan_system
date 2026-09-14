package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.OverdueStatsVO;
import com.loan.service.OverdueStatsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/loan/overdue")
@Tag(name = "逾期统计模块", description = "逾期数据统计：核心指标、趋势图、金额分布")
public class OverdueStatsController {

    @Resource
    private OverdueStatsService overdueStatsService;

    @GetMapping("/stats")
    @Operation(summary = "获取逾期统计数据", description = "返回逾期核心指标、月度逾期率趋势、逾期金额分布")
    public Result<OverdueStatsVO> getStats(
            @RequestParam(required = false) String startDate,
            @RequestParam(required = false) String endDate) {
        return overdueStatsService.getStats(startDate, endDate);
    }
}

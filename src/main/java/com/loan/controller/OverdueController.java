package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.service.impl.OverdueCheckService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逾期管理控制器
 */
@Tag(name = "逾期管理模块", description = "逾期检查相关接口")
@RestController
@RequestMapping("/api/v1/loan/overdue")
public class OverdueController {

    @Resource
    private OverdueCheckService overdueCheckService;

    /**
     * 手动触发逾期检查
     */
    @PostMapping("/check")
    @Operation(summary = "手动触发逾期检查", description = "立即执行逾期检测任务")
    public Result<?> manualCheck() {
        overdueCheckService.manualCheckOverdue();
        return Result.success("逾期检查已完成");
    }
}

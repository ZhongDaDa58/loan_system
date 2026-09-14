package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.service.impl.OverdueCheckService;
import com.loan.service.impl.RepaymentReminderService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 逾期管理控制器
 */
@Tag(name = "逾期管理模块", description = "逾期检查、还款提醒相关接口")
@RestController
@RequestMapping("/api/v1/loan/overdue")
public class OverdueController {

    @Resource
    private OverdueCheckService overdueCheckService;

    @Resource
    private RepaymentReminderService repaymentReminderService;

    /**
     * 手动触发逾期检查
     */
    @PostMapping("/check")
    @Operation(summary = "手动触发逾期检查", description = "立即执行逾期检测任务")
    public Result<?> manualCheck() {
        overdueCheckService.manualCheckOverdue();
        return Result.success("逾期检查已完成");
    }

    /**
     * 手动触发还款提醒
     */
    @PostMapping("/send-reminders")
    @Operation(summary = "手动触发还款提醒", description = "立即发送未来 3 天到期的还款提醒通知")
    public Result<?> sendReminders() {
        repaymentReminderService.manualSendReminders();
        return Result.success("还款提醒已发送");
    }
}

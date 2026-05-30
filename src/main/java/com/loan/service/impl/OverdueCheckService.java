package com.loan.service.impl;

import com.loan.entity.MonthlyRepayment;
import com.loan.entity.enums.RepaymentStatusEnum;
import com.loan.mapper.MonthlyRepaymentMapper;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;

/**
 * 逾期检测服务
 * 每天凌晨 2 点自动检查未还款记录，将超过到期日的标记为逾期
 */
@Service
public class OverdueCheckService {

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    /**
     * 定时任务：每天凌晨 2 点执行逾期检查
     * Cron 表达式：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void checkOverdue() {
        Date now = new Date();

        // 查询所有未还款的记录
        List<MonthlyRepayment> unpaidList = monthlyRepaymentMapper.selectByStatus(
                RepaymentStatusEnum.UNPAID.getCode()
        );

        if (unpaidList == null || unpaidList.isEmpty()) {
            return;
        }

        int overdueCount = 0;
        for (MonthlyRepayment repayment : unpaidList) {
            // 如果当前时间超过到期还款日，则标记为逾期
            if (now.after(repayment.getDueDate())) {
                int rows = monthlyRepaymentMapper.updateRepaymentStatus(
                        repayment.getRepaymentId(),
                        RepaymentStatusEnum.OVERDUE.getCode(),
                        now
                );
                if (rows > 0) {
                    overdueCount++;
                    System.out.println("标记逾期：" + repayment.getRepaymentId() +
                            ", 期数：" + repayment.getTerm() +
                            ", 到期日：" + repayment.getDueDate());
                }
            }
        }

        if (overdueCount > 0) {
            System.out.println("逾期检查完成，共标记 " + overdueCount + " 笔逾期记录");
        }
    }

    /**
     * 手动触发逾期检查（用于测试或紧急情况）
     */
    public void manualCheckOverdue() {
        checkOverdue();
    }
}

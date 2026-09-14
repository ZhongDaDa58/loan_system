package com.loan.service.impl;

import com.loan.entity.vo.UpcomingRepaymentVO;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.NotificationMapper;
import com.loan.service.NotificationService;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.text.SimpleDateFormat;
import java.util.List;

/**
 * 还款提前提醒服务
 * 每天上午 9:00 检查未来 3 天内到期的未还款项，发送 App 内通知
 */
@Service
public class RepaymentReminderService {

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private NotificationMapper notificationMapper;

    @Resource
    private NotificationService notificationService;

    /**
     * 定时任务：每天 9:00 发送还款提醒
     */
    @Scheduled(cron = "0 0 9 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void sendRepaymentReminders() {
        // 查询未来 3 天内到期的未还款记录
        List<UpcomingRepaymentVO> upcomingList = monthlyRepaymentMapper.selectUpcomingUnpaid(3);

        if (upcomingList == null || upcomingList.isEmpty()) {
            return;
        }

        SimpleDateFormat dateFormat = new SimpleDateFormat("MM月dd日");
        int sentCount = 0;

        for (UpcomingRepaymentVO repayment : upcomingList) {
            // 去重：同一笔还款只发一次提醒
            int existing = notificationMapper.countByTypeAndRelatedId(
                    "repayment_reminder", repayment.getRepaymentId());
            if (existing > 0) {
                continue;
            }

            String dueDateStr = repayment.getDueDate() != null
                    ? dateFormat.format(repayment.getDueDate()) : "";

            notificationService.sendNotification(
                    repayment.getUserId(),
                    "还款提醒",
                    "您有一笔 " + repayment.getRepaymentAmount() + " 元的还款将于 "
                            + dueDateStr + " 到期，请确保账户余额充足。",
                    "repayment_reminder",
                    repayment.getRepaymentId()
            );
            sentCount++;
        }

        if (sentCount > 0) {
            System.out.println("还款提醒完成，共发送 " + sentCount + " 条提醒");
        }
    }

    /**
     * 手动触发还款提醒（用于测试）
     */
    public void manualSendReminders() {
        sendRepaymentReminders();
    }
}

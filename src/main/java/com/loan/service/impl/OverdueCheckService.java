package com.loan.service.impl;

import lombok.extern.slf4j.Slf4j;

import com.loan.entity.MonthlyRepayment;
import com.loan.entity.enums.RepaymentStatusEnum;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.NotificationMapper;
import com.loan.mapper.RepaymentPlanMapper;
import com.loan.service.NotificationService;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.List;

/**
 * 逾期检测与分级（逾期处理功能升级 第 2 步）
 *
 * <p><b>这一步修的是整个逾期体系的地基断点</b>：
 * <pre>
 * 原实现：selectByStatus('unpaid')  ← 只扫未还款
 *         ↓ 逾期后状态改成 'overdue'
 *         ↓ 第二天再跑，这个条件扫不到它了
 *         ↓ 逾期天数从来没被记录 → 无法分级 → 罚息/差异化管理全部无从谈起
 * </pre>
 *
 * <p><b>现在</b>：扫 {@code unpaid} + {@code overdue}（{@link MonthlyRepaymentMapper#selectOverdueCandidates()}），
 * 每天按 {@code due_date} 重算天数、更新 M1/M2/M3 等级。
 *
 * <p><b>三个关键设计</b>：
 * <ol>
 *   <li><b>等级放独立列，不动 {@code repayment_status}</b> ——
 *       status 被还款、展示等多处引用，改成 {@code overdue_m1/m2/m3} 会破坏所有现有判断</li>
 *   <li><b>只在「首次逾期」或「等级跃迁」时通知</b> —— 否则用户每天被推一次，{@code notification} 表也会爆。
 *       通知的 type 带等级（{@code overdue_m1/m2/m3}），配合 {@code countByTypeAndRelatedId} 天然去重</li>
 *   <li><b>幂等</b> —— 同一天重复触发，天数与等级结果相同（重算得同值），不会产生副作用；
 *       通知靠 type+relatedId 去重。罚息未在此步实现（第 3 步，靠 {@code penalty_last_calc_date} 幂等）</li>
 * </ol>
 *
 * @see <a href="file:../../../../../finance_dev/逾期处理功能升级开发.md">逾期处理功能升级开发.md §3.2</a>
 */
@Service
@Slf4j
public class OverdueCheckService {

    /** 等级分界（天） */
    private static final int M2_FROM = 31;
    private static final int M3_FROM = 61;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private RepaymentPlanMapper repaymentPlanMapper;

    @Resource
    private NotificationService notificationService;

    @Resource
    private NotificationMapper notificationMapper;

    /**
     * 定时任务：每天凌晨 2 点执行逾期检查。
     *
     * <p>Cron：秒 分 时 日 月 周
     */
    @Scheduled(cron = "0 0 2 * * ?")
    @Transactional(rollbackFor = Exception.class)
    public void checkOverdue() {
        List<MonthlyRepayment> candidates = monthlyRepaymentMapper.selectOverdueCandidates();
        if (candidates == null || candidates.isEmpty()) {
            log.info("逾期检查完成：无待处理记录");
            return;
        }

        LocalDate today = LocalDate.now();
        int updated = 0;
        int notified = 0;

        for (MonthlyRepayment r : candidates) {
            LocalDate due = toLocalDate(r.getDueDate());
            if (due == null) {
                continue;
            }
            long days = ChronoUnit.DAYS.between(due, today);
            // ⚠️ 当天到期不算逾期（第二天才算）—— 避免"今天还没过完就开始催"
            if (days < 1) {
                continue;
            }

            String newLevel = levelOf((int) days);
            String oldLevel = r.getOverdueLevel();
            boolean levelChanged = !newLevel.equals(oldLevel);

            monthlyRepaymentMapper.updateOverdueTracking(
                    r.getRepaymentId(), (int) days, newLevel, levelChanged);
            updated++;

            // 首次逾期（unpaid → overdue）或等级跃迁时才通知
            boolean firstOverdue = !RepaymentStatusEnum.OVERDUE.getCode()
                    .equals(r.getRepaymentStatus());
            if ((firstOverdue || levelChanged) && sendOverdueNotice(r, (int) days, newLevel)) {
                notified++;
            }
        }

        log.info("逾期检查完成：扫描 {} 条，更新 {} 条，发送通知 {} 条",
                candidates.size(), updated, notified);
    }

    /**
     * 手动触发逾期检查（用于测试或紧急情况）。
     *
     * <p>与定时任务同一实现 —— 保证"手动验证"和"线上行为"完全一致。
     */
    public void manualCheckOverdue() {
        checkOverdue();
    }

    // ==================== 内部 ====================

    /** 按天数定级：M1(1-30) / M2(31-60) / M3(60+) */
    private static String levelOf(int days) {
        if (days >= M3_FROM) {
            return "M3";
        }
        if (days >= M2_FROM) {
            return "M2";
        }
        return "M1";
    }

    /**
     * 发逾期通知。
     *
     * <p><b>去重靠 type 带等级</b>：{@code overdue_m1/m2/m3} —— 同一期同一等级只发一次。
     * 这样即使 {@code overdue_level_changed_at} 因并发被重复写，也不会重复推送。
     *
     * @return 是否真的发出去了（被去重跳过时返回 false）
     */
    private boolean sendOverdueNotice(MonthlyRepayment r, int days, String level) {
        String type = "overdue_" + level.toLowerCase();
        try {
            // 去重：同等级已发过就不再发
            if (notificationMapper.countByTypeAndRelatedId(type, r.getRepaymentId()) > 0) {
                return false;
            }
            String userId = repaymentPlanMapper.selectUserIdByPlanId(r.getPlanId());
            if (userId == null) {
                log.warn("逾期通知跳过：找不到还款计划对应的用户 planId={}", r.getPlanId());
                return false;
            }
            notificationService.sendNotification(
                    userId,
                    noticeTitle(level),
                    noticeBody(r, days, level),
                    type,
                    r.getRepaymentId());
            return true;
        } catch (Exception e) {
            // 通知失败不影响逾期标记本身（标记才是资金/风控相关的那部分）
            log.error("发送逾期通知失败 repaymentId={}", r.getRepaymentId(), e);
            return false;
        }
    }

    private static String noticeTitle(String level) {
        return switch (level) {
            case "M3" -> "严重逾期提醒";
            case "M2" -> "逾期升级提醒";
            default -> "还款逾期提醒";
        };
    }

    private static String noticeBody(MonthlyRepayment r, int days, String level) {
        String extra = switch (level) {
            case "M3" -> "已属严重逾期，将影响您的信用及后续借款资格。";
            case "M2" -> "逾期已超过一个月，请尽快处理以免进一步影响信用。";
            default -> "请尽快还款以免影响信用。";
        };
        return "您第 " + r.getTerm() + " 期还款（" + r.getRepaymentAmount() + " 元）"
                + "已逾期 " + days + " 天，" + extra;
    }

    /** {@code due_date} 是 DATE 列，映射到 java.util.Date；统一转 LocalDate 做天数计算 */
    private static LocalDate toLocalDate(Date d) {
        if (d == null) {
            return null;
        }
        if (d instanceof java.sql.Date sqlDate) {
            return sqlDate.toLocalDate();
        }
        return d.toInstant().atZone(ZoneId.systemDefault()).toLocalDate();
    }
}

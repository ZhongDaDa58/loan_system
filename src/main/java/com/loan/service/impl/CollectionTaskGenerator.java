package com.loan.service.impl;

import com.loan.entity.CollectionTask;
import com.loan.entity.MonthlyRepayment;
import com.loan.mapper.CollectionTaskMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.RepaymentPlanMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * 催收任务生成器（逾期处理功能升级 第 6 步-c）
 *
 * <p>每天凌晨扫一遍逾期记录，按等级自动铺催收任务 —— 没有这一步，
 * 催收员得自己从逾期列表里一个个挑，实际用起来没人会这么做。
 *
 * <p><b>为什么独立成一个类，而不是塞进 OverdueCheckService</b>：
 * 逾期扫描的职责是"算天数和等级"，催收是"要不要打电话"。
 * 塞在一起会让 OverdueCheckService 同时承担两个业务域，而且定时时间也不同。
 *
 * <p><b>建任务的规则</b>：
 * <table>
 *   <tr><th>等级</th><th>逾期天数</th><th>是否建任务</th><th>优先级</th></tr>
 *   <tr><td>M1</td><td>&lt; 7 天</td><td>不建</td><td>—</td></tr>
 *   <tr><td>M1</td><td>&ge; 7 天</td><td>建</td><td>3（低）</td></tr>
 *   <tr><td>M2</td><td>31-60</td><td>建</td><td>2（中）</td></tr>
 *   <tr><td>M3</td><td>&gt; 60</td><td>建</td><td>1（高）</td></tr>
 * </table>
 *
 * <p><b>M1 前 7 天不建任务</b>是刻意的：刚逾期一两天大多是用忘还款，自动提醒
 * （{@code RepaymentReminderService}）已经够了，这时候就打电话会让客户反感。
 *
 * <p><b>已有 open 任务不重复建、也不自动改优先级</b>：
 * 优先级允许人工调整（长期失联的老案子要上调），自动覆盖会跟人打架。
 * 等级变化体现在 {@code overdue_days} 上，催收员看天数就知道严重程度。
 */
@Component
public class CollectionTaskGenerator {

    private static final Logger log = LoggerFactory.getLogger(CollectionTaskGenerator.class);

    /** M1 早于这个天数不建任务（自动提醒已经够了） */
    private static final int M1_MIN_DAYS = 7;

    /** 生成后的首次跟进日期：当天就要跟（催收讲究时效） */
    private static final int FIRST_FOLLOW_OFFSET_DAYS = 0;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Resource
    private RepaymentPlanMapper repaymentPlanMapper;

    @Resource
    private CollectionTaskMapper collectionTaskMapper;

    /**
     * 每天 3:30 生成催收任务。
     *
     * <p>时间选择：逾期扫描是 2:00、多源同步 2:30 —— 必须在它们之后，
     * 否则拿到的是昨天的逾期天数，M1/M2/M3 判断会差一天。
     */
    @Scheduled(cron = "0 30 3 * * ?")
    public void scheduledGenerate() {
        try {
            int created = generate();
            log.info("催收任务生成完成：新建 {} 个", created);
        } catch (Exception e) {
            // 定时任务里的异常不能往外抛，否则会被调度器记成任务失败
            log.error("催收任务生成失败", e);
        }
    }

    /**
     * 执行一次生成（便于手动触发和测试）。
     *
     * @return 新建的任务数
     */
    public int generate() {
        List<MonthlyRepayment> candidates = monthlyRepaymentMapper.selectOverdueCandidates();
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        LocalDate today = LocalDate.now();
        int created = 0;
        for (MonthlyRepayment r : candidates) {
            int days = r.getOverdueDays() == null ? 0 : r.getOverdueDays();
            String level = r.getOverdueLevel();
            if (level == null || days <= 0) {
                continue;
            }
            // M1 前两天不打扰
            if ("M1".equals(level) && days < M1_MIN_DAYS) {
                continue;
            }

            String userId = repaymentPlanMapper.selectUserIdByPlanId(r.getPlanId());
            if (userId == null) {
                log.warn("催收任务跳过：plan {} 找不到 userId", r.getPlanId());
                continue;
            }

            CollectionTask task = new CollectionTask();
            task.setTaskId("CT-" + UUID.randomUUID().toString().replace("-", "").substring(0, 20));
            task.setUserId(userId);
            task.setRepaymentId(r.getRepaymentId());
            task.setPlanId(r.getPlanId());
            task.setOverdueLevel(level);
            task.setOverdueDays(days);
            task.setPriority(defaultPriority(level));
            task.setNextFollowDate(java.sql.Date.valueOf(
                    today.plusDays(FIRST_FOLLOW_OFFSET_DAYS)));

            if (collectionTaskMapper.insertIfNoOpenTask(task) > 0) {
                created++;
            }
        }
        return created;
    }

    /** 按等级给默认优先级：M3 最高优先（1），M2 中（2），M1 低（3） */
    private static int defaultPriority(String level) {
        return switch (level) {
            case "M3" -> 1;
            case "M2" -> 2;
            default -> 3;
        };
    }
}

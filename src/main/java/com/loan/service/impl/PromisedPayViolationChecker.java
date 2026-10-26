package com.loan.service.impl;

import com.loan.entity.CollectionTask;
import com.loan.mapper.CollectionTaskMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 承诺违约检查器（逾期处理功能升级 第 6 步-c 的收尾）
 *
 * <p><b>为什么需要它</b>：催收员让客户承诺一个还款日，这个动作本身没有强制力 ——
 * 如果到期没还却没人发现，那个"承诺"就是个空记录，催收员下次打电话时
 * 还以为是第一次谈。本类每天检查一次，把违约的承诺标出来并把任务拉回今日待办。
 *
 * <h3>违约后做三件事</h3>
 * <ol>
 *   <li>{@code promise_violated = 1} —— 催收员一眼看到"这个人说话不算数"，
 *       下次沟通策略要变</li>
 *   <li>优先级提升一级（3→2、2→1、1 不再升）—— 违约的案子该往前排</li>
 *   <li>{@code next_follow_date = 今天}，状态回 {@code in_progress} ——
 *       承诺已失效，必须重新跟进</li>
 * </ol>
 *
 * <p><b>刻意不做的两件事</b>：
 * <ul>
 *   <li>不改 {@code overdue_level} —— 那是 {@code OverdueCheckService} 的职责，
 *       这里改会造成两个地方都在算等级，口径迟早不一致</li>
 *   <li>不自动写 {@code collection_record} —— 那是人工跟进的留痕，
 *       塞一条系统记录进去会污染"实际联系过几次"的统计</li>
 * </ul>
 *
 * <p><b>幂等</b>靠 SQL 里的 {@code WHERE promise_violated = 0}：
 * 否则每天都把优先级降一格，几天后所有任务都变最高优先级，优先级就失去意义了。
 */
@Component
public class PromisedPayViolationChecker {

    private static final Logger log = LoggerFactory.getLogger(PromisedPayViolationChecker.class);

    /** 优先级下限（1 = 最高，不能再升） */
    private static final int HIGHEST_PRIORITY = 1;

    @Resource
    private CollectionTaskMapper collectionTaskMapper;

    /**
     * 每天 3:35 检查承诺违约。
     *
     * <p>时间在催收任务生成（3:30）之后 —— 刚建的任务还没有承诺日期，
     * 先建后查的顺序也更符合直觉。
     */
    @Scheduled(cron = "0 35 3 * * ?")
    public void scheduledCheck() {
        try {
            int violated = check();
            if (violated > 0) {
                log.info("承诺违约检查完成：标记 {} 条", violated);
            }
        } catch (Exception e) {
            // 定时任务里的异常不能往外抛
            log.error("承诺违约检查失败", e);
        }
    }

    /**
     * 执行一次检查（便于手动触发和测试）。
     *
     * @return 本次标记为违约的任务数
     */
    @Transactional(rollbackFor = Exception.class)
    public int check() {
        List<CollectionTask> candidates = collectionTaskMapper.selectViolatedPromises();
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }

        int violated = 0;
        for (CollectionTask task : candidates) {
            int oldPriority = task.getPriority() == null ? 2 : task.getPriority();
            int newPriority = Math.max(HIGHEST_PRIORITY, oldPriority - 1);

            if (collectionTaskMapper.markPromiseViolated(task.getTaskId(), newPriority) > 0) {
                violated++;
                log.info("承诺违约：taskId={}, 承诺日={}, 优先级 {} -> {}, 已拉回今日待办",
                        task.getTaskId(), task.getPromisedPayDate(), oldPriority, newPriority);
            }
        }
        return violated;
    }
}

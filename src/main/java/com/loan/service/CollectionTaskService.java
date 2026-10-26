package com.loan.service;

import com.loan.entity.CollectionTask;

import java.time.LocalDate;
import java.util.List;

/**
 * 催收任务服务（逾期处理功能升级 第 6 步-c）
 */
public interface CollectionTaskService {

    /** 按业务 ID 查 */
    CollectionTask getByTaskId(String taskId);

    /**
     * 分页查任务。
     *
     * @param status        状态过滤，null=不限
     * @param assignedTo    负责人过滤，null=不限
     * @param dueFollowOnly true=只看今天该跟进的（催收员的"今日待办"）
     */
    List<CollectionTask> list(String status, String assignedTo, boolean dueFollowOnly,
                              int page, int size);

    /** 与 {@link #list} 条件一致的总数 */
    long count(String status, String assignedTo, boolean dueFollowOnly);

    /**
     * 认领任务（把 assignedTo 设成自己，状态转 in_progress）。
     *
     * @return false 表示任务不存在或已关闭
     */
    boolean claim(String taskId, String operator);

    /**
     * 更新跟进信息：下次跟进日 / 承诺还款日 / 状态。
     *
     * @param promisedPayDate 客户承诺的还款日；非空时会把 promise_violated 清 0
     *                        （新承诺覆盖旧的违约标记）
     * @return false 表示任务不存在或已关闭
     */
    boolean updateFollow(String taskId, String assignedTo, String status,
                         LocalDate nextFollowDate, LocalDate promisedPayDate);

    /**
     * 关闭任务（必须给原因）。
     *
     * @return false 表示任务不存在或本来就已经关闭
     */
    boolean close(String taskId, String reason);

    /**
     * 客户还款成功后，关闭该期次的所有 open 任务。
     *
     * <p>由还款链路调用。返回被关闭的任务数（0 表示这期本来就没有催收任务，属正常）。
     */
    int closeByRepayment(String repaymentId, String reason);
}

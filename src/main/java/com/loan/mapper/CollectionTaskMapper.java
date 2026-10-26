package com.loan.mapper;

import com.loan.entity.CollectionTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 催收任务 Mapper（逾期处理功能升级 第 6 步-c）
 */
@Mapper
public interface CollectionTaskMapper {

    /**
     * 建任务 —— 如果该期次已有 open 任务（pending/in_progress/promised）则不建。
     *
     * @return 影响行数；<b>0 表示已有 open 任务</b>，不是错误
     */
    int insertIfNoOpenTask(CollectionTask task);

    /** 按业务 ID 查 */
    CollectionTask selectByTaskId(@Param("taskId") String taskId);

    /** 查某期次当前 open 的任务（用于"还款后自动关闭"等场景） */
    CollectionTask selectOpenByRepaymentId(@Param("repaymentId") String repaymentId);

    /**
     * 分页查任务。
     *
     * @param status        状态过滤，null 表示不限
     * @param assignedTo    负责人过滤，null 表示不限
     * @param dueFollowOnly true=只查今天该跟进的（next_follow_date &lt;= 今天）
     */
    List<CollectionTask> selectPage(@Param("status") String status,
                                    @Param("assignedTo") String assignedTo,
                                    @Param("dueFollowOnly") boolean dueFollowOnly,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    /** 分页总数（条件与 selectPage 一致） */
    long countPage(@Param("status") String status,
                   @Param("assignedTo") String assignedTo,
                   @Param("dueFollowOnly") boolean dueFollowOnly);

    /**
     * 更新任务的跟进字段（认领 / 记录承诺 / 设下次跟进）。
     *
     * @return 影响行数；0 表示任务不存在或已关闭
     */
    int updateFollowInfo(@Param("taskId") String taskId,
                         @Param("assignedTo") String assignedTo,
                         @Param("status") String status,
                         @Param("nextFollowDate") java.time.LocalDate nextFollowDate,
                         @Param("promisedPayDate") java.time.LocalDate promisedPayDate,
                         @Param("promiseViolated") Integer promiseViolated);

    /**
     * 关闭任务（必须给原因）。
     *
     * @return 影响行数；0 表示任务不存在或本来就已经关闭
     */
    int closeTask(@Param("taskId") String taskId,
                  @Param("reason") String reason);

    /**
     * 批量关闭某期次的 open 任务 —— 客户还款成功后调用。
     *
     * @return 被关闭的任务数
     */
    int closeByRepaymentId(@Param("repaymentId") String repaymentId,
                           @Param("reason") String reason);

    /**
     * 查"承诺了还款、到期却没还"的任务（承诺违约检查用）。
     *
     * <p>JOIN 还款明细判断是否真的还了 —— 光看 {@code promised_pay_date} 不够：
     * 有些还款路径可能没走到 {@link #closeByRepaymentId}，任务还开着但钱已经还了。
     */
    List<CollectionTask> selectViolatedPromises();

    /**
     * 标记承诺违约并提升优先级（条件 UPDATE，幂等）。
     *
     * <p>{@code WHERE promise_violated = 0} —— 同一条任务只处理一次，
     * 否则每天都把优先级往下降一格，很快就全变最高优先了。
     *
     * @param newPriority 新的优先级（1 高 / 2 中 / 3 低），由调用方按原值计算
     * @return 1=本次标记成功；0=已被处理过或任务已关闭
     */
    int markPromiseViolated(@Param("taskId") String taskId,
                            @Param("newPriority") int newPriority);
}

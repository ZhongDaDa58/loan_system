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
}

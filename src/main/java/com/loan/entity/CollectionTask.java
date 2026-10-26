package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 催收任务（逾期处理功能升级 第 6 步-c）
 *
 * <p><b>按「还款期次」建，不是按「用户」建</b> —— 一个客户可能有 3 期都逾期，
 * 催收员需要分别跟（第 2 期可能已经协商好、第 3 期还没谈）。按用户建会丢掉这个粒度。
 *
 * <p><b>状态机</b>：
 * <pre>
 *   pending（待跟进） ──认领──> in_progress（跟进中） ──客户答应还──> promised（已承诺）
 *        │                          │                                  │
 *        └────────────── 关闭（必填原因）──────────────────────────────┘
 *                                   ↓
 *                              closed（已关闭）
 * </pre>
 * 承诺违约（{@code promised_pay_date} 过了还没还）不会自动改状态，
 * 而是置 {@code promise_violated=1} 并把优先级调高 —— 让催收员看到"这个人说话不算数"。
 */
@Data
public class CollectionTask {

    private Long id;

    /** 业务 ID（对外暴露，不暴露自增主键） */
    private String taskId;

    /** 欠款用户 */
    private String userId;

    /** 对应的还款期次 */
    private String repaymentId;

    /** 还款计划 ID（冗余，省一次 JOIN） */
    private String planId;

    /** 生成时的逾期等级 M1/M2/M3 */
    private String overdueLevel;

    /** 生成时的逾期天数（快照 —— 任务列表不必每次回表算） */
    private Integer overdueDays;

    /** 1=高 2=中 3=低。生成时按等级给默认值，之后允许人工调整 */
    private Integer priority;

    /** pending / in_progress / promised / closed */
    private String status;

    /** 负责的催收员 */
    private String assignedTo;

    /** 下次跟进日期（到期会出现在"今日待办"） */
    private Date nextFollowDate;

    /** 最新一次客户承诺的还款日 */
    private Date promisedPayDate;

    /** 承诺是否已违约（1=违约） */
    private Integer promiseViolated;

    /** 最后一次跟进时间 */
    private Date lastFollowAt;

    /** 关闭原因（关闭时必填） */
    private String closeReason;

    private Date closedAt;

    private Date createTime;

    private Date updateTime;
}

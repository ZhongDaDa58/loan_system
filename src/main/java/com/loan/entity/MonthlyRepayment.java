package com.loan.entity;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class MonthlyRepayment {
    private String repaymentId;     // 还款明细ID
    private String planId;          // 还款计划ID
    private Integer term;           // 期数
    private BigDecimal principal;   // 当期本金
    private BigDecimal interest;    // 当期利息
    private BigDecimal repaymentAmount; // 当期还款金额
    private Date dueDate;           // 到期还款日
    private String repaymentStatus; // 还款状态（unpaid / paid / overdue）
    private Date repaymentTime;     // 实际还款时间

    // ==================== 逾期跟踪字段（阶段：逾期处理功能升级 第 1 步，V4 迁移）====================

    /**
     * 逾期天数。未逾期为 0，每日由 {@code OverdueCheckService} 回写。
     *
     * <p><b>为什么不实时算</b>：实时算无法索引、无法做 M1/M2/M3 分级统计、
     * 也无法支撑"等级跃迁"这类只在变化时触发的动作。
     */
    private Integer overdueDays;

    /**
     * 逾期等级：{@code M1}(1-30) / {@code M2}(31-60) / {@code M3}(60+)，未逾期为 {@code null}。
     *
     * <p><b>与 {@code repaymentStatus} 的分工</b>：
     * <ul>
     *   <li>{@code repaymentStatus} —— 状态机（unpaid / paid / overdue），被还款、展示等多处引用</li>
     *   <li>{@code overdueLevel} —— 严重程度，只升不降；结清时清空</li>
     * </ul>
     * 两者分开存，避免"把 status 改成 overdue_m1/m2/m3"破坏所有现有判断。
     */
    private String overdueLevel;

    /** 累计罚息（单利按日累加）。未逾期或已结清为 0。 */
    private BigDecimal penaltyAmount;

    /**
     * 罚息最后计算日期 —— <b>幂等的关键</b>。
     *
     * <p>定时任务每天跑；若同一天被手动触发一次 + 定时触发一次，没有这个字段就会
     * <b>重复计息</b>，罚息越滚越多。计算前先判断"今天是否已算过"。
     */
    private Date penaltyLastCalcDate;

    /** 最近一次等级跃迁时间 —— 用于"只在跃迁时通知"，避免每日骚扰用户。 */
    private Date overdueLevelChangedAt;

    /** 已扣过信用分的等级 —— 保证同一等级只扣一次分（幂等）。 */
    private String scoreDeductedLevel;
}

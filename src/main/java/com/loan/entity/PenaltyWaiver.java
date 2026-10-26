package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 罚息减免申请单（逾期处理功能升级 第 6 步-d）
 *
 * <p><b>这张表存在的意义</b>：让"改罚息"这件事有单据、有审批人、有原因。
 * 有了它，{@code monthly_repayment.penalty_amount} 就不应该再被任何代码直接改小 ——
 * 唯一合法的路径是「审批通过」（见 {@code PenaltyWaiverServiceImpl#approve}）。
 *
 * <p><b>为什么存快照</b>：罚息每天都在涨。申请时客户看到 50 元、审批时可能已经 62 元。
 * 存 {@link #penaltySnapshot} 是为了事后能回答"当时批的是基于多少钱批的"，
 * 而不是拿今天的数字去解释昨天的决定。
 *
 * <p><b>审批通过时按 {@link #waivedAmount} 减，不按快照减</b> ——
 * 客户申请的是具体金额，快照只用于核对。
 */
@Data
public class PenaltyWaiver {

    private Long id;

    /** 业务 ID（对外暴露） */
    private String waiverId;

    /** 客户 */
    private String userId;

    /** 还款期次 */
    private String repaymentId;

    /** 还款计划（冗余，便于查询） */
    private String planId;

    /** 申请减免的罚息金额 */
    private BigDecimal waivedAmount;

    /** 申请那一刻的罚息余额（快照，用于事后核对） */
    private BigDecimal penaltySnapshot;

    /** 减免原因（必填，展示给审批人） */
    private String reason;

    /** pending=待审批 / approved=已通过 / rejected=已驳回 / cancelled=已撤销 */
    private String status;

    /** 申请人 */
    private String applicant;

    /** 审批人（必须与申请人不同 —— 自己申请自己批等于没有审批） */
    private String approver;

    /** 审批意见 */
    private String approveRemark;

    private Date approvedAt;

    private Date createTime;

    private Date updateTime;
}

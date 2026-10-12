package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class RiskDecisionLog {
    private Long id;

    private String applicationId;

    private String userId;

    private Integer scorecardScore;

    private String scorecardDecision;

    private String decisionReason;

    private String featureDetails;

    private String validationErrors;

    private String consistencyWarnings;

    /**
     * 评分所用模型版本（W4 多模型化）：如 v3 / v4 / v4-fusion
     */
    private String modelVersion;

    /**
     * 评分所用特征快照（JSON）：便于回溯"这笔决策用了哪些特征值"
     */
    private String featureSnapshot;

    // ===== 阶段 2：记录本次生效的产品级阈值（阈值分化可追溯） =====

    /** 本次决策使用的产品 ID */
    private String productId;

    /** 本次生效的自动通过线 */
    private Integer appliedAutoPass;

    /** 本次生效的人工审核线 */
    private Integer appliedManualReview;

    /** 阈值来源：PRODUCT（产品配置） / DEFAULT（全局默认） */
    private String thresholdSource;

    private Date decisionTime;

    private Date createTime;
}

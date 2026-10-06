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

    private Date decisionTime;

    private Date createTime;
}

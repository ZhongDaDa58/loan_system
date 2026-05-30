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

    private Date decisionTime;

    private Date createTime;
}

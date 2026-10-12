package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 规则命中流水（阶段 3）
 * 对应表：risk_rule_hit_log
 */
@Data
public class RiskRuleHitLog {

    private Long id;

    private String applicationId;

    private String userId;

    private Long ruleId;

    private String ruleCode;

    private String ruleName;

    /** L1 / L3 */
    private String layer;

    /** 命中动作 */
    private String action;

    /** 是否影子命中（未生效） */
    private Boolean shadow;

    private Date hitTime;
}

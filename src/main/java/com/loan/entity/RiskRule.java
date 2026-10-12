package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 风险规则（阶段 1 · 规则层）
 * 对应表：risk_rule
 *
 * 说明：条件为**受限表达式文本**（field op value [AND|OR ...]），由 RiskRuleEngine 解析求值，
 *      不使用脚本引擎（避免注入风险）。
 */
@Data
public class RiskRule {

    private Long id;

    /** 规则编号（如 L1-01） */
    private String ruleCode;

    /** 规则名称 */
    private String name;

    /** 分类（前端分类树）：反欺诈 / 信用风险 / 观察 */
    private String category;

    /** 层级：L1=前置拦截 / L3=后置修正 */
    private String layer;

    /** 触发条件（列 condition_text，对外字段名仍为 condition） */
    private String condition;

    /** 动作：REJECT / MANUAL / DOWNGRADE / FLAG */
    private String action;

    /** 动作参数（如降额比例） */
    private String actionParam;

    /** 优先级（数值越小越先执行） */
    private Integer priority;

    /** 是否启用（true=启用） */
    private Boolean enabled;

    /** 是否影子模式（true=只记录不生效） */
    private Boolean shadowMode;

    private String remark;

    private Date createTime;

    private Date updateTime;
}

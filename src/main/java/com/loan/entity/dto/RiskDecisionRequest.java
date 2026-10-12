package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.math.BigDecimal;

/**
 * 统一风险决策请求（阶段 2 · 决策编排）
 *
 * 用于"L1 规则 → 特征/评分 → L3 规则 → 最终决策"的一站式调用。
 */
@Data
public class RiskDecisionRequest {

    /** 用户 ID（必填，用于取特征与评分） */
    @NotBlank(message = "userId 不能为空")
    private String userId;

    /** 申请 ID（可选；提供时写入决策留痕） */
    private String applicationId;

    /** 产品 ID（可选；用于产品阈值解析） */
    private String productId;

    /** 申请金额（可选；供规则条件使用） */
    private BigDecimal amount;

    /** 指定模型版本（可选；如 v3 / v4） */
    private String modelVersion;
}

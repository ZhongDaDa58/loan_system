package com.loan.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class LoanProduct {
    private String productId;       // 产品ID
    private String productName;     // 产品名称
    private BigDecimal minAmount;   // 最小金额
    private BigDecimal maxAmount;   // 最大金额
    private Integer minTerm;        // 最小期限（月）
    private Integer maxTerm;        // 最大期限（月）
    private BigDecimal interestRate;// 年利率
    private Integer status;         // 状态（0-下架，1-上架）
    private Integer autoPassScore;  // 自动通过分数线（>=此分数自动通过）
    private Integer manualReviewScore; // 人工审核最低分数线（>=此分数且<autoPassScore转人工）

    // ===== 阶段 2：产品阈值分化扩展字段（可空，向后兼容） =====
    private BigDecimal targetBadRate;   // 目标坏账率（如 0.0300 = 3%），阈值标定输入
    private BigDecimal targetPassRate;  // 最低目标通过率（约束下界，可空）
    private String thresholdSource;     // 阈值来源：MANUAL / CALIBRATED / DEFAULT
    private Date thresholdUpdatedAt;    // 阈值最近变更时间

    private Date createTime;
    private Date updateTime;

}

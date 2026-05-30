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
    private Date createTime;
    private Date updateTime;

}
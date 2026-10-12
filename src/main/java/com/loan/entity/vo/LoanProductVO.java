package com.loan.entity.vo;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class LoanProductVO {
    private String productId;
    private String productName;
    private BigDecimal interestRate;
    private Integer status; // 0-下架，1-上架
    private BigDecimal minAmount;   // 最小金额
    private BigDecimal maxAmount;   // 最大金额
    private Integer minTerm;        // 最小期限（月）
    private Integer maxTerm;        // 最大期限（月）
    private java.util.Date createTime;
    private java.util.Date updateTime;
    private Integer autoPassScore;
    private Integer manualReviewScore;

    // ===== 阶段 2：阈值分化相关字段 =====
    private BigDecimal targetBadRate;      // 目标坏账率
    private BigDecimal targetPassRate;     // 最低目标通过率
    private String thresholdSource;        // 阈值来源：MANUAL / CALIBRATED / DEFAULT
    private java.util.Date thresholdUpdatedAt; // 阈值最近变更时间

    private String interestRateDesc; // 利率描述（如“6.5%/年”）
    private String amountRange;      // 金额范围（如“1000-50000元”）
    private String termRange;        // 期限范围（如“3-12个月”）
    // 构造展示字段（避免前端拼接）
    public void setInterestRate(BigDecimal interestRate) {
        this.interestRate = interestRate;
        this.interestRateDesc = interestRate + "%/年";
    }

    public void setAmountRange(BigDecimal min, BigDecimal max) {
        this.amountRange = min + "-" + max + "元";
    }

    public void setTermRange(Integer min, Integer max) {
        this.termRange = min + "-" + max + "个月";
    }
}

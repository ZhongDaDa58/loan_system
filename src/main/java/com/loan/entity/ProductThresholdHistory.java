package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 产品阈值变更历史（阶段 2 · 阈值分化可追溯）
 * 对应表：product_threshold_history
 */
@Data
public class ProductThresholdHistory {

    private Long id;

    /** 产品 ID */
    private String productId;

    /** 自动通过线 */
    private Integer autoPassScore;

    /** 人工审核线 */
    private Integer manualReviewScore;

    /** 标定时的目标坏账率 */
    private BigDecimal targetBadRate;

    /** 阈值来源：MANUAL / CALIBRATED / DEFAULT */
    private String thresholdSource;

    /** 变更原因 */
    private String changeReason;

    /** 操作人 */
    private String changedBy;

    private Date createTime;
}

package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 产品阈值标定建议（P0 · 阈值治理）
 * 对应表：product_threshold_suggestion
 */
@Data
public class ProductThresholdSuggestion {

    private Long id;

    private String productId;

    /** 建议自动通过线 */
    private Integer suggestedAutoPass;

    /** 建议人工审核线 */
    private Integer suggestedManualReview;

    /** 标定时的目标坏账率 */
    private BigDecimal targetBadRate;

    /** 标定集实测坏账率 */
    private BigDecimal expectedBadRate;

    /** 标定集实测通过率 */
    private BigDecimal expectedPassRate;

    /** 标定样本量 */
    private Integer sampleSize;

    /** 标定时间 */
    private Date calibratedAt;

    /** 说明（如"产品分层为模拟"） */
    private String note;

    /** 0=未采纳 1=已采纳 */
    private Integer adopted;

    private Date adoptedAt;

    private Date createTime;
}

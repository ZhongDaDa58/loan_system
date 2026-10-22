package com.loan.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

/**
 * 精细化定价档位（设计 §2：「最终决策与**精细化定价**」）
 *
 * <p>按评分区间匹配年化利率；{@code productId} 为 {@code null} 表示全局档位。
 * 与「规则降额」（{@code approved_amount}）思路一致：**配置驱动、可在线调整**。
 *
 * <p>对应表：{@code loan_pricing_tier}
 */
@Data
public class LoanPricingTier {

    private Long id;

    /** 产品ID；null = 全局档位（对所有产品生效，可被产品专属档位覆盖） */
    private String productId;

    /** 评分下限（含） */
    private Integer minScore;

    /** 评分上限（含） */
    private Integer maxScore;

    /** 年化利率（0.0960 = 9.60%） */
    private BigDecimal annualRate;

    /** 档位标签（A/B/C…） */
    private String tierLabel;

    private String remark;

    private Integer enabled;

    private Date createTime;

    private Date updateTime;
}

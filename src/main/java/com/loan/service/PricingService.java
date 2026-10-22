package com.loan.service;

import com.loan.entity.LoanPricingTier;
import com.loan.mapper.LoanPricingTierMapper;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * 精细化定价服务（设计 §2：「最终决策与**精细化定价**」）
 *
 * <p><b>职责</b>：按「产品 + 评分」从 {@code loan_pricing_tier} 匹配年化利率。
 * 与「规则降额」（{@code approved_amount}）思路一致 —— **配置驱动、可在线调整**：
 * 档位表可在数据库直接维护，无需改代码发版。
 *
 * <p><b>降级</b>：无匹配档位 / 分数为空 / 查询异常 → 返回未命中，
 * 调用方回退 {@code loan_product.interest_rate}，**现有行为不变**。
 */
@Service
public class PricingService {

    private static final Logger log = LoggerFactory.getLogger(PricingService.class);

    @Resource
    private LoanPricingTierMapper pricingTierMapper;

    /**
     * 定价结果
     *
     * @param annualRate 年化利率；{@code null} 表示未命中（回退产品利率）
     * @param tierLabel  档位标签（A/B/C…），未命中时为 {@code null}
     */
    public record PricingOutcome(BigDecimal annualRate, String tierLabel) {
        public boolean matched() {
            return annualRate != null;
        }
    }

    /**
     * 按产品与评分匹配精细化定价档位。
     *
     * @param productId 产品ID（可为 null）
     * @param score     评分卡分数（为 null 时不匹配）
     * @return 定价结果；未命中时 {@code annualRate = null}
     */
    public PricingOutcome resolveRate(String productId, Integer score) {
        if (score == null) {
            return new PricingOutcome(null, null);
        }
        try {
            LoanPricingTier tier = pricingTierMapper.selectMatching(productId, score);
            if (tier == null || tier.getAnnualRate() == null) {
                log.info("💱 精细化定价未命中档位（score={} product={}）→ 回退产品利率", score, productId);
                return new PricingOutcome(null, null);
            }
            log.info("💱 精细化定价命中：score={} → 档位 {} / 年化 {}",
                    score, tier.getTierLabel(), tier.getAnnualRate());
            return new PricingOutcome(tier.getAnnualRate(), tier.getTierLabel());
        } catch (Exception e) {
            log.warn("⚠️ 精细化定价匹配异常（回退产品利率）: {}", e.getMessage());
            return new PricingOutcome(null, null);
        }
    }
}

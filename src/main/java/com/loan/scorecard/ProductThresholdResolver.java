package com.loan.scorecard;

import com.loan.entity.LoanProduct;
import org.springframework.stereotype.Component;

/**
 * 产品级阈值解析（阶段 2 · 产品阈值分化）
 *
 * 规则：
 *  1) 产品已配置 auto_pass_score / manual_review_score → 使用**产品阈值**（source=PRODUCT）；
 *  2) 产品未配置 → 回退**全局默认阈值**（source=DEFAULT），保证链路可用。
 *
 * 说明：默认值与当前线上模型的 thresholds 保持一致（v3: 650/600；v4: 641/504），
 *      切换默认模型时需同步更新本类常量（或在后续版本改为读取模型配置）。
 */
@Component
public class ProductThresholdResolver {

    /** 全局默认：自动通过线（与 v3 model_config.thresholds.auto_approve 一致） */
    private static final int DEFAULT_AUTO_PASS = 650;

    /** 全局默认：人工审核线（与 v3 model_config.thresholds.manual_review 一致） */
    private static final int DEFAULT_MANUAL_REVIEW = 600;

    /**
     * 生效阈值结果
     *
     * @param autoPassScore     自动通过线（分数 ≥ 该值 → 自动通过）
     * @param manualReviewScore 人工审核线（分数 ≥ 该值且 &lt; 自动通过线 → 转人工；否则拒绝）
     * @param source            来源：PRODUCT / DEFAULT
     */
    public record ResolvedThresholds(int autoPassScore, int manualReviewScore, String source) {
    }

    /**
     * 解析产品生效阈值（含防御：保证 manual ≤ auto）
     */
    public ResolvedThresholds resolve(LoanProduct product) {
        if (product != null
                && product.getAutoPassScore() != null
                && product.getManualReviewScore() != null) {
            int auto = product.getAutoPassScore();
            int manual = Math.min(product.getManualReviewScore(), auto);
            return new ResolvedThresholds(auto, manual, "PRODUCT");
        }
        return new ResolvedThresholds(DEFAULT_AUTO_PASS, DEFAULT_MANUAL_REVIEW, "DEFAULT");
    }
}

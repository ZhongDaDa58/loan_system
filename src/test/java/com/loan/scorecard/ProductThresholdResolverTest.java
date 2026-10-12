package com.loan.scorecard;

import com.loan.entity.LoanProduct;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 产品阈值解析用例（阶段 2 · 产品阈值分化）
 *
 * 覆盖：产品配置优先 / 缺失回退默认 / 非法组合防御（manual &gt; auto）
 */
class ProductThresholdResolverTest {

    private final ProductThresholdResolver resolver = new ProductThresholdResolver();

    @Test
    void shouldUseProductThresholdsWhenConfigured() {
        LoanProduct product = new LoanProduct();
        product.setProductId("product_001");
        product.setAutoPassScore(643);
        product.setManualReviewScore(563);

        ProductThresholdResolver.ResolvedThresholds resolved = resolver.resolve(product);

        assertEquals(643, resolved.autoPassScore());
        assertEquals(563, resolved.manualReviewScore());
        assertEquals("PRODUCT", resolved.source());
    }

    @Test
    void shouldFallbackToDefaultWhenNotConfigured() {
        // 产品未配置阈值（两者均为 null）
        ProductThresholdResolver.ResolvedThresholds resolved = resolver.resolve(new LoanProduct());

        assertEquals(650, resolved.autoPassScore());
        assertEquals(600, resolved.manualReviewScore());
        assertEquals("DEFAULT", resolved.source());

        // 产品为 null 时同样走默认
        assertEquals("DEFAULT", resolver.resolve(null).source());
    }

    @Test
    void shouldDefendManualReviewGreaterThanAutoPass() {
        LoanProduct product = new LoanProduct();
        product.setAutoPassScore(700);
        product.setManualReviewScore(800); // 历史脏数据组合

        ProductThresholdResolver.ResolvedThresholds resolved = resolver.resolve(product);

        assertEquals(700, resolved.autoPassScore());
        assertEquals(700, resolved.manualReviewScore(), "防御：人工审核线应被压到不高于自动通过线");
        assertEquals("PRODUCT", resolved.source());
    }
}

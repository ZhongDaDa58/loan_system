package com.loan.mapper;

import com.loan.entity.LoanPricingTier;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 精细化定价档位 Mapper
 *
 * <p>对应表：{@code loan_pricing_tier}（设计 §2「精细化定价」）
 */
@Mapper
public interface LoanPricingTierMapper {

    /**
     * 按「产品 + 评分」匹配定价档位。
     *
     * <p>匹配规则：{@code enabled=1} 且分数落在 {@code [min_score, max_score]}；
     * **产品专属档位优先**，其次全局档位（{@code product_id IS NULL}）。
     *
     * @return 命中的档位；无匹配返回 {@code null}（调用方回退产品利率）
     */
    LoanPricingTier selectMatching(@Param("productId") String productId,
                                   @Param("score") Integer score);

    /** 全部档位（供管理端展示/维护） */
    List<LoanPricingTier> selectAll();

    int insert(LoanPricingTier tier);

    int update(LoanPricingTier tier);

    int deleteById(@Param("id") Long id);
}

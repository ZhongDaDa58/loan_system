package com.loan.service.impl;

import com.loan.entity.LoanProduct;
import com.loan.entity.ProductThresholdHistory;
import com.loan.entity.ProductThresholdSuggestion;
import com.loan.mapper.LoanProductMapper;
import com.loan.mapper.ProductThresholdHistoryMapper;
import com.loan.mapper.ProductThresholdSuggestionMapper;
import com.loan.service.ThresholdGovernanceService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 阈值治理服务实现（P0）
 */
@Service
public class ThresholdGovernanceServiceImpl implements ThresholdGovernanceService {

    private static final Logger log = LoggerFactory.getLogger(ThresholdGovernanceServiceImpl.class);

    @Resource
    private ProductThresholdSuggestionMapper suggestionMapper;

    @Resource
    private LoanProductMapper loanProductMapper;

    @Resource
    private ProductThresholdHistoryMapper thresholdHistoryMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int importSuggestions(List<ProductThresholdSuggestion> suggestions) {
        if (suggestions == null || suggestions.isEmpty()) {
            return 0;
        }
        int imported = 0;
        for (ProductThresholdSuggestion suggestion : suggestions) {
            if (suggestion == null
                    || suggestion.getProductId() == null
                    || suggestion.getSuggestedAutoPass() == null
                    || suggestion.getSuggestedManualReview() == null) {
                continue;
            }
            // 防御：保证 manual ≤ auto（与产品阈值校验口径一致）
            if (suggestion.getSuggestedManualReview() > suggestion.getSuggestedAutoPass()) {
                suggestion.setSuggestedManualReview(suggestion.getSuggestedAutoPass());
            }
            suggestion.setId(null);
            suggestion.setAdopted(0);
            suggestion.setAdoptedAt(null);
            if (suggestion.getCreateTime() == null) {
                suggestion.setCreateTime(new Date());
            }
            if (suggestion.getCalibratedAt() == null) {
                suggestion.setCalibratedAt(new Date());
            }
            suggestionMapper.insert(suggestion);
            imported++;
        }
        log.info("📥 导入标定建议 {} 条", imported);
        return imported;
    }

    @Override
    public Map<String, Object> getSuggestion(String productId) {
        LoanProduct product = loanProductMapper.selectById(productId);
        if (product == null) {
            return null;
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);

        Map<String, Object> current = new LinkedHashMap<>();
        current.put("autoPassScore", product.getAutoPassScore());
        current.put("manualReviewScore", product.getManualReviewScore());
        current.put("targetBadRate", product.getTargetBadRate());
        current.put("thresholdSource", product.getThresholdSource());
        current.put("thresholdUpdatedAt", product.getThresholdUpdatedAt());
        result.put("current", current);

        ProductThresholdSuggestion latest = suggestionMapper.selectLatestByProductId(productId);
        result.put("suggestion", latest);

        if (latest != null) {
            Map<String, Object> diff = new LinkedHashMap<>();
            boolean same = Objects.equals(product.getAutoPassScore(), latest.getSuggestedAutoPass())
                    && Objects.equals(product.getManualReviewScore(), latest.getSuggestedManualReview());
            diff.put("alreadyAdopted", same);
            diff.put("autoPassDelta", delta(product.getAutoPassScore(), latest.getSuggestedAutoPass()));
            diff.put("manualReviewDelta", delta(product.getManualReviewScore(), latest.getSuggestedManualReview()));
            result.put("diff", diff);
        }
        return result;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> adoptSuggestion(String productId, String reason) {
        LoanProduct product = loanProductMapper.selectById(productId);
        if (product == null) {
            return null;
        }
        ProductThresholdSuggestion latest = suggestionMapper.selectLatestByProductId(productId);
        if (latest == null) {
            return null;
        }

        product.setAutoPassScore(latest.getSuggestedAutoPass());
        product.setManualReviewScore(latest.getSuggestedManualReview());
        if (latest.getTargetBadRate() != null) {
            product.setTargetBadRate(latest.getTargetBadRate());
        }
        product.setThresholdSource("CALIBRATED");
        product.setThresholdUpdatedAt(new Date());
        loanProductMapper.updateProduct(product);

        // 写入阈值变更历史（来源=标定采纳）
        try {
            ProductThresholdHistory history = new ProductThresholdHistory();
            history.setProductId(productId);
            history.setAutoPassScore(latest.getSuggestedAutoPass());
            history.setManualReviewScore(latest.getSuggestedManualReview());
            history.setTargetBadRate(latest.getTargetBadRate());
            history.setThresholdSource("CALIBRATED");
            history.setChangeReason(reason == null || reason.isBlank() ? "采纳标定建议" : reason);
            history.setChangedBy("admin");
            history.setCreateTime(new Date());
            thresholdHistoryMapper.insert(history);
        } catch (Exception e) {
            log.warn("⚠️ 阈值变更留痕写入失败（不影响采纳）: {}", e.getMessage());
        }

        suggestionMapper.markAdopted(latest.getId());
        log.info("✅ 已采纳标定建议: 产品={} → {}/{}",
                productId, latest.getSuggestedAutoPass(), latest.getSuggestedManualReview());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productId", productId);
        result.put("autoPassScore", product.getAutoPassScore());
        result.put("manualReviewScore", product.getManualReviewScore());
        result.put("thresholdSource", product.getThresholdSource());
        result.put("adoptedSuggestionId", latest.getId());
        result.put("expectedBadRate", latest.getExpectedBadRate());
        result.put("expectedPassRate", latest.getExpectedPassRate());
        return result;
    }

    private Integer delta(Integer current, Integer suggested) {
        if (current == null || suggested == null) {
            return null;
        }
        return suggested - current;
    }
}

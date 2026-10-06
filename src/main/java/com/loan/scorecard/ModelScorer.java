package com.loan.scorecard;

import com.loan.scorecard.model.ScorecardModel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 通用评分计算器（W4 · 主线 A）
 *
 * 与既有 {@code ScorecardServiceImpl} 的算法口径**完全一致**（bins + score_mapping，
 * totalScore = A - B*intercept + Σ 各特征分箱得分），但**特征集不再硬编码**：
 * 按模型配置里声明的特征逐个取值，因此可同时服务 v3（6 特征）与 v4（21/22 特征）。
 *
 * 缺失特征的处理：记为 missingFeatures 并按 0 分计入（调用方可据此提示"需先同步多源数据"）。
 */
@Component
public class ModelScorer {

    private static final Logger log = LoggerFactory.getLogger(ModelScorer.class);

    /** 评分归一化区间（与现网一致） */
    private static final int MIN_SCORE = 300;
    private static final int MAX_SCORE = 950;

    public record ScoreOutcome(int totalScore,
                               String decision,
                               String decisionDesc,
                               String scoreLevel,
                               Map<String, Object> featureScores,
                               List<String> missingFeatures) {
    }

    /**
     * 按指定模型对特征打分。
     *
     * @param model    已加载的模型配置
     * @param features 特征键值（键名与模型配置中的 features 键一致）
     */
    public ScoreOutcome score(ScorecardModel model, Map<String, Object> features) {
        if (model == null || !model.isLoaded()) {
            throw new IllegalStateException("模型未就绪，无法评分");
        }
        Map<String, Object> safeFeatures = features == null ? Map.of() : features;

        double base = model.a() - model.b() * model.intercept();
        Map<String, Object> featureScores = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        double sum = 0.0;

        for (String feature : model.featureNames()) {
            Object raw = safeFeatures.get(feature);
            if (raw == null) {
                missing.add(feature);
                featureScores.put(feature, 0.0);
                continue;
            }
            Double value = toDouble(raw);
            if (value == null) {
                missing.add(feature);
                featureScores.put(feature, 0.0);
                continue;
            }
            double score = featureScore(model, feature, value);
            featureScores.put(feature, score);
            sum += score;
        }

        int total = normalize((int) Math.round(base + sum));
        String decision = decision(model, total);

        if (!missing.isEmpty()) {
            log.info("📊 评分完成（模型 {}）：分数 {}，缺失特征 {} 个: {}",
                    model.getModelKey(), total, missing.size(), missing);
        }
        return new ScoreOutcome(total, decision, decisionDesc(decision), level(total), featureScores, missing);
    }

    // ==================== 内部实现 ====================

    @SuppressWarnings("unchecked")
    private double featureScore(ScorecardModel model, String featureName, double value) {
        Map<String, Object> featureConfig = (Map<String, Object>) model.getFeatures().get(featureName);
        if (featureConfig == null) {
            return 0;
        }
        List<Object> bins = (List<Object>) featureConfig.get("bins");
        Map<String, Object> scoreMapping = (Map<String, Object>) featureConfig.get("score_mapping");
        if (bins == null || scoreMapping == null || bins.size() < 2) {
            return 0;
        }

        List<String> keys = new ArrayList<>(scoreMapping.keySet());
        for (int i = 0; i < bins.size() - 1; i++) {
            double lower = parseBinBound(String.valueOf(bins.get(i)));
            double upper = parseBinBound(String.valueOf(bins.get(i + 1)));

            boolean inRange;
            if (i == 0) {
                inRange = value <= upper;
            } else if (i == bins.size() - 2) {
                inRange = value > lower;
            } else {
                inRange = value > lower && value <= upper;
            }

            if (inRange && i < keys.size()) {
                Object score = scoreMapping.get(keys.get(i));
                return score instanceof Number n ? n.doubleValue() : 0;
            }
        }
        log.debug("⚠️ 特征 {} 的值 {} 未命中任何分箱，按 0 分处理", featureName, value);
        return 0;
    }

    private double parseBinBound(String bound) {
        if ("-inf".equals(bound) || "-∞".equals(bound)) {
            return Double.NEGATIVE_INFINITY;
        }
        if ("inf".equals(bound) || "+inf".equals(bound) || "∞".equals(bound)) {
            return Double.POSITIVE_INFINITY;
        }
        try {
            return Double.parseDouble(bound);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    private Double toDouble(Object raw) {
        if (raw instanceof Number n) {
            return n.doubleValue();
        }
        if (raw instanceof String s) {
            try {
                return Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private int normalize(int score) {
        return Math.max(MIN_SCORE, Math.min(MAX_SCORE, score));
    }

    private String decision(ScorecardModel model, int score) {
        if (score >= model.autoApprove()) {
            return "AUTO_APPROVE";
        }
        if (score >= model.manualReview()) {
            return "MANUAL_REVIEW";
        }
        return "REJECT";
    }

    private String decisionDesc(String decision) {
        return switch (decision) {
            case "AUTO_APPROVE" -> "自动通过";
            case "MANUAL_REVIEW" -> "人工审核";
            case "REJECT" -> "拒绝";
            default -> "未知";
        };
    }

    private String level(int score) {
        if (score >= 750) {
            return "优秀";
        }
        if (score >= 650) {
            return "良好";
        }
        if (score >= 600) {
            return "一般";
        }
        if (score >= 550) {
            return "较差";
        }
        return "很差";
    }
}

package com.loan.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.ScorecardResultVO;
import com.loan.exception.BusinessException;
import com.loan.service.ScorecardService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ScorecardServiceImpl implements ScorecardService {

    private static final Logger log = LoggerFactory.getLogger(ScorecardServiceImpl.class);

    private Map<String, Object> modelConfig;
    private Map<String, Object> scoreScale;
    private Map<String, Object> coefficients;
    private Map<String, Object> features;
    private Map<String, Object> thresholds;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ScorecardServiceImpl() {
        log.info("🔧 ScorecardServiceImpl 构造函数被调用");
    }

    @PostConstruct
    public void init() {
        log.info("🚀 开始初始化评分卡模型...");
        try {
            ClassPathResource resource = new ClassPathResource("model_config.json");
            log.info("📂 尝试加载文件: {}", resource.getPath());

            if (!resource.exists()) {
                log.error("❌ model_config.json 文件不存在！");
                throw new RuntimeException("model_config.json 文件不存在");
            }

            InputStream inputStream = resource.getInputStream();
            log.info("✅ 文件读取成功，开始解析 JSON...");

            modelConfig = objectMapper.readValue(inputStream, new TypeReference<Map<String, Object>>() {});
            log.info("✅ JSON 解析成功");

            scoreScale = (Map<String, Object>) modelConfig.get("score_scale");
            coefficients = (Map<String, Object>) modelConfig.get("coefficients");
            features = (Map<String, Object>) modelConfig.get("features");
            thresholds = (Map<String, Object>) modelConfig.get("thresholds");

            log.info("✅ 评分卡模型加载成功: {}", modelConfig.get("model_info"));
            log.info("📊 模型版本: {}", ((Map<?, ?>) modelConfig.get("model_info")).get("version"));
            log.info("🎯 审批阈值 - 自动通过: {}, 人工审核: {}",
                    thresholds.get("auto_approve"), thresholds.get("manual_review"));
            log.info("📈 特征数量: {}", features.size());
        } catch (IOException e) {
            log.error("❌ 评分卡模型加载失败: {}", e.getMessage(), e);
            throw new RuntimeException("评分卡模型加载失败", e);
        } catch (Exception e) {
            log.error("❌ 评分卡模型初始化异常: {}", e.getMessage(), e);
            throw new RuntimeException("评分卡模型初始化异常", e);
        }
    }

    @Override
    public ScorecardResultVO calculateScore(UserProfileDTO userProfile) {
        validateUserProfile(userProfile);

        int totalScore = calculateRawScore(userProfile);
        totalScore = normalizeScore(totalScore);

        String decision = determineDecision(totalScore);
        String scoreLevel = getScoreLevel(totalScore);
        Map<String, Object> featureScores = calculateFeatureScoresForDisplay(userProfile);

        return ScorecardResultVO.builder()
                .totalScore(totalScore)
                .scoreLevel(scoreLevel)
                .decision(decision)
                .decisionDesc(getDecisionDesc(decision))
                .featureScores(featureScores)
                .build();
    }

    @Override
    public Integer calculateScoreOnly(UserProfileDTO userProfile) {
        validateUserProfile(userProfile);
        int totalScore = calculateRawScore(userProfile);
        return normalizeScore(totalScore);
    }

    private void validateUserProfile(UserProfileDTO userProfile) {
        if (userProfile.getRevolvingUtil() == null || userProfile.getAge() == null ||
                userProfile.getDebtRatio() == null || userProfile.getCreditLines() == null ||
                userProfile.getDependents() == null) {
            throw new BusinessException(400, "用户画像数据不完整");
        }
    }

    private int calculateRawScore(UserProfileDTO userProfile) {
        if (scoreScale == null || features == null) {
            log.error("❌ 模型未正确初始化！");
            throw new BusinessException(500, "评分卡模型未初始化");
        }

        double A = ((Number) scoreScale.get("A")).doubleValue();
        double intercept = ((Number) coefficients.get("intercept")).doubleValue();
        double interceptScore = ((Number)scoreScale.get("B")).doubleValue() * intercept;

        double revolvingUtilScore = getFeatureScoreDirect("revolving_util", userProfile.getRevolvingUtil().doubleValue());
        double ageScore = getFeatureScoreDirect("age", userProfile.getAge().doubleValue());
        double debtRatioScore = getFeatureScoreDirect("debt_ratio", userProfile.getDebtRatio().doubleValue());
        double monthlyIncomeScore = userProfile.getMonthlyIncome() != null ?
                getFeatureScoreDirect("monthly_income", userProfile.getMonthlyIncome().doubleValue()) : 0;
        double creditLinesScore = getFeatureScoreDirect("credit_lines", userProfile.getCreditLines().doubleValue());
        double dependentsScore = getFeatureScoreDirect("dependents", userProfile.getDependents().doubleValue());

        log.info("📊 特征分数 - revolvingUtil: {}, age: {}, debtRatio: {}, monthlyIncome: {}, creditLines: {}, dependents: {}",
                revolvingUtilScore, ageScore, debtRatioScore, monthlyIncomeScore, creditLinesScore, dependentsScore);

        int score = (int) Math.round(A - interceptScore + revolvingUtilScore + ageScore + debtRatioScore
                + monthlyIncomeScore + creditLinesScore + dependentsScore);

        log.info("🎯 原始分数: {}, 归一化后: {}", score, normalizeScore(score));

        return score;
    }

    private double getFeatureScoreDirect(String featureName, double value) {
        Map<String, Object> featureConfig = (Map<String, Object>) features.get(featureName);
        if (featureConfig == null) {
            log.error("❌ 找不到特征配置: {}", featureName);
            return 0;
        }

        List<Object> bins = (List<Object>) featureConfig.get("bins");
        Map<String, Object> scoreMapping = (Map<String, Object>) featureConfig.get("score_mapping");

        for (int i = 0; i < bins.size() - 1; i++) {
            double lowerBound = parseBinBound(bins.get(i).toString());
            double upperBound = parseBinBound(bins.get(i + 1).toString());

            boolean inRange = false;
            if (i == 0) {
                inRange = value <= upperBound;
            } else if (i == bins.size() - 2) {
                inRange = value > lowerBound;
            } else {
                inRange = value > lowerBound && value <= upperBound;
            }

            if (inRange) {
                List<String> keys = new ArrayList<>(scoreMapping.keySet());
                if (i < keys.size()) {
                    Number score = (Number) scoreMapping.get(keys.get(i));
                    log.debug("✅ 特征: {} 索引: {}, 得分: {}", featureName, i, score);
                    return score != null ? score.doubleValue() : 0;
                }
            }
        }

        log.warn("⚠️ 特征: {} 的值 {} 没有匹配到任何分箱！", featureName, value);
        return 0;
    }

    private double parseBinBound(String bound) {
        if ("-inf".equals(bound) || "-∞".equals(bound)) {
            return Double.NEGATIVE_INFINITY;
        } else if ("inf".equals(bound) || "+inf".equals(bound) || "∞".equals(bound)) {
            return Double.POSITIVE_INFINITY;
        }
        return Double.parseDouble(bound);
    }

    private int normalizeScore(int score) {
        return Math.max(300, Math.min(950, score));
    }

    private String determineDecision(int score) {
        Number autoApprove = (Number) thresholds.get("auto_approve");
        Number manualReview = (Number) thresholds.get("manual_review");

        if (score >= autoApprove.intValue()) {
            return "AUTO_APPROVE";
        } else if (score >= manualReview.intValue()) {
            return "MANUAL_REVIEW";
        } else {
            return "REJECT";
        }
    }

    private String getDecisionDesc(String decision) {
        switch (decision) {
            case "AUTO_APPROVE":
                return "自动通过";
            case "MANUAL_REVIEW":
                return "人工审核";
            case "REJECT":
                return "拒绝";
            default:
                return "未知";
        }
    }

    private String getScoreLevel(int score) {
        if (score >= 750) {
            return "优秀";
        } else if (score >= 650) {
            return "良好";
        } else if (score >= 600) {
            return "一般";
        } else if (score >= 550) {
            return "较差";
        } else {
            return "很差";
        }
    }

    private Map<String, Object> calculateFeatureScoresForDisplay(UserProfileDTO userProfile) {
        Map<String, Object> featureScores = new LinkedHashMap<>();

        featureScores.put("revolving_util", getFeatureScoreDirect("revolving_util", userProfile.getRevolvingUtil().doubleValue()));
        featureScores.put("age", getFeatureScoreDirect("age", userProfile.getAge().doubleValue()));
        featureScores.put("debt_ratio", getFeatureScoreDirect("debt_ratio", userProfile.getDebtRatio().doubleValue()));
        featureScores.put("monthly_income", userProfile.getMonthlyIncome() != null ?
                getFeatureScoreDirect("monthly_income", userProfile.getMonthlyIncome().doubleValue()) : 0);
        featureScores.put("credit_lines", getFeatureScoreDirect("credit_lines", userProfile.getCreditLines().doubleValue()));
        featureScores.put("dependents", getFeatureScoreDirect("dependents", userProfile.getDependents().doubleValue()));

        return featureScores;
    }
}

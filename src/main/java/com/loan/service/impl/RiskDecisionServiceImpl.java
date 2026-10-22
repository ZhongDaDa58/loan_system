package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.LoanProduct;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.RiskRule;
import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.dto.RiskDecisionRequest;
import com.loan.entity.vo.MultiModelScoreResultVO;
import com.loan.mapper.RiskDecisionLogMapper;
import com.loan.mapper.RiskRuleMapper;
import com.loan.rule.RiskRuleEngine;
import com.loan.scorecard.ProductThresholdResolver;
import com.loan.service.FeatureTransformationService;
import com.loan.service.LoanProductService;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.RiskDecisionService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一风险决策服务实现（阶段 2 · 决策编排）
 */
@Service
public class RiskDecisionServiceImpl implements RiskDecisionService {

    private static final Logger log = LoggerFactory.getLogger(RiskDecisionServiceImpl.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private FeatureTransformationService featureTransformationService;

    @Resource
    private MultiModelScorecardService multiModelScorecardService;

    @Resource
    private ProductThresholdResolver productThresholdResolver;

    @Resource
    private LoanProductService loanProductService;

    @Resource
    private RiskRuleMapper riskRuleMapper;

    @Resource
    private RiskRuleEngine riskRuleEngine;

    @Resource
    private RiskDecisionLogMapper riskDecisionLogMapper;

    /** 阶段 3：规则命中流水 */
    @Resource
    private com.loan.mapper.RiskRuleHitLogMapper riskRuleHitLogMapper;

    @Override
    public Map<String, Object> decide(RiskDecisionRequest request) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", request.getUserId());
        result.put("applicationId", request.getApplicationId());
        result.put("productId", request.getProductId());

        // 1) 特征装配 + 规则上下文
        Map<String, Object> features = featureTransformationService.buildMultiSourceFeatures(request.getUserId());
        Map<String, Object> context = new LinkedHashMap<>(features);
        if (request.getAmount() != null) {
            context.put("applyAmount", request.getAmount());
        }
        if (request.getProductId() != null) {
            context.put("productId", request.getProductId());
        }
        result.put("featureCount", features.size());

        // 2) L1 前置拦截（命中 REJECT 则不进入评分）
        RiskRuleEngine.RuleOutcome l1 = safeEvaluate("L1", context);
        result.put("l1", toView(l1));
        if ("REJECT".equals(l1.action())) {
            result.put("score", null);
            result.put("finalDecision", "REJECT");
            result.put("finalDecisionDesc", "拒绝（L1 规则前置拦截，未进入评分）");
            writeLog(request, null, "REJECT", null, null, l1, null, features);
            log.info("🚫 决策=REJECT（L1 拦截） userId={} applicationId={}",
                    request.getUserId(), request.getApplicationId());
            return result;
        }

        // 3) 评分（多模型引擎，默认模型）
        // 注意：此处**不传 applicationId** —— 留痕由编排层统一写入，避免同一笔决策产生两条记录
        MultiModelScoreRequest scoreRequest = new MultiModelScoreRequest();
        scoreRequest.setFeatures(features);
        scoreRequest.setUserId(request.getUserId());
        scoreRequest.setModelVersion(request.getModelVersion());

        MultiModelScoreResultVO scoreResult = multiModelScorecardService.calculate(scoreRequest);
        Integer score = scoreResult.getTotalScore();
        context.put("score", score);
        result.put("score", score);

        Map<String, Object> scorecard = new LinkedHashMap<>();
        scorecard.put("modelKey", scoreResult.getModelKey());
        scorecard.put("modelVersion", scoreResult.getModelVersion());
        scorecard.put("decision", scoreResult.getDecision());
        scorecard.put("missingFeatures", scoreResult.getMissingFeatures());
        result.put("scorecard", scorecard);

        // 4) 产品阈值基准决策（产品配置优先 → 全局默认回退）
        LoanProduct product = null;
        if (request.getProductId() != null && !request.getProductId().isBlank()) {
            try {
                product = loanProductService.validateProduct(request.getProductId());
            } catch (Exception e) {
                log.warn("⚠️ 产品 {} 不存在或不可用，按全局默认阈值处理: {}",
                        request.getProductId(), e.getMessage());
            }
        }
        ProductThresholdResolver.ResolvedThresholds thresholds = productThresholdResolver.resolve(product);

        String baseDecision = decideByThreshold(score, thresholds);
        result.put("baseDecision", baseDecision);
        result.put("baseDecisionDesc", describe(baseDecision));

        Map<String, Object> thresholdView = new LinkedHashMap<>();
        thresholdView.put("autoPass", thresholds.autoPassScore());
        thresholdView.put("manualReview", thresholds.manualReviewScore());
        thresholdView.put("source", thresholds.source());
        result.put("thresholds", thresholdView);

        // 5) L3 后置修正（只改动作，不改分数）
        RiskRuleEngine.RuleOutcome l3 = safeEvaluate("L3", context);
        result.put("l3", toView(l3));

        String finalDecision = applyRuleAction(baseDecision, l3.action());
        result.put("finalDecision", finalDecision);
        result.put("finalDecisionDesc", describe(finalDecision));

        // 6) 统一留痕
        writeLog(request, score, finalDecision, thresholds, scoreResult, l1, l3, features);

        log.info("🎯 决策={} 分数={} 阈值={}/{}({}) userId={}",
                finalDecision, score, thresholds.autoPassScore(), thresholds.manualReviewScore(),
                thresholds.source(), request.getUserId());
        return result;
    }

    // ==================== 内部方法 ====================

    private RiskRuleEngine.RuleOutcome safeEvaluate(String layer, Map<String, Object> context) {
        try {
            List<RiskRule> rules = riskRuleMapper.selectEnabledByLayer(layer);
            return riskRuleEngine.evaluate(rules, context);
        } catch (Exception e) {
            log.warn("⚠️ 规则层 {} 评估异常（按无命中处理）: {}", layer, e.getMessage());
            return new RiskRuleEngine.RuleOutcome(new ArrayList<>(), null, null, null);
        }
    }

    /** 产品阈值基准决策 */
    private String decideByThreshold(Integer score, ProductThresholdResolver.ResolvedThresholds thresholds) {
        if (score == null) {
            return "UNKNOWN";
        }
        if (score >= thresholds.autoPassScore()) {
            return "AUTO_PASS";
        }
        if (score >= thresholds.manualReviewScore()) {
            return "MANUAL_REVIEW";
        }
        return "AUTO_REJECT";
    }

    /** 用 L3 规则动作修正基准决策 */
    private String applyRuleAction(String baseDecision, String ruleAction) {
        if (ruleAction == null || ruleAction.isBlank()) {
            return baseDecision;
        }
        switch (ruleAction) {
            case "REJECT":
                return "REJECT";
            case "MANUAL":
                return "MANUAL_REVIEW";
            case "DOWNGRADE":
                return "AUTO_REJECT".equals(baseDecision) ? baseDecision : "APPROVE_DOWNGRADE";
            case "FLAG":
            default:
                return baseDecision;
        }
    }

    private String describe(String decision) {
        if (decision == null) {
            return "未知";
        }
        switch (decision) {
            case "AUTO_PASS":
                return "自动通过";
            case "MANUAL_REVIEW":
                return "转人工审核";
            case "AUTO_REJECT":
                return "拒绝";
            case "REJECT":
                return "拒绝（规则拦截）";
            case "APPROVE_DOWNGRADE":
                return "通过（降额）";
            default:
                return decision;
        }
    }

    private Map<String, Object> toView(RiskRuleEngine.RuleOutcome outcome) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("action", outcome.action());
        List<Map<String, Object>> hits = new ArrayList<>();
        if (outcome.hits() != null) {
            for (RiskRuleEngine.RuleHit hit : outcome.hits()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleCode", hit.ruleCode());
                item.put("name", hit.name());
                item.put("action", hit.action());
                item.put("shadow", hit.shadow());
                hits.add(item);
            }
        }
        view.put("hits", hits);
        view.put("hitCount", hits.size());
        return view;
    }

    private void writeLog(RiskDecisionRequest request, Integer score, String decision,
                          ProductThresholdResolver.ResolvedThresholds thresholds,
                          MultiModelScoreResultVO scoreResult,
                          RiskRuleEngine.RuleOutcome l1, RiskRuleEngine.RuleOutcome l3,
                          Map<String, Object> features) {
        if (request.getApplicationId() == null || request.getApplicationId().isBlank()) {
            return; // 未关联申请单时不落留痕
        }
        try {
            RiskDecisionLog entity = new RiskDecisionLog();
            entity.setApplicationId(request.getApplicationId());
            entity.setUserId(request.getUserId());
            entity.setScorecardScore(score);
            entity.setScorecardDecision(decision);
            entity.setDecisionReason(buildReason(l1, l3));
            entity.setModelVersion(scoreResult == null ? null : scoreResult.getModelKey());
            entity.setFeatureSnapshot(toJson(features));
            entity.setProductId(request.getProductId());
            if (thresholds != null) {
                entity.setAppliedAutoPass(thresholds.autoPassScore());
                entity.setAppliedManualReview(thresholds.manualReviewScore());
                entity.setThresholdSource(thresholds.source());
            }
            entity.setDecisionTime(new Date());
            entity.setCreateTime(new Date());
            riskDecisionLogMapper.insert(entity);
            log.info("📝 决策留痕已写入（统一入口）: applicationId={}", request.getApplicationId());

            // 阶段 3：写规则命中流水（供命中率 / 误杀率统计）
            recordHits(request, l1);
            recordHits(request, l3);
        } catch (Exception e) {
            log.warn("⚠️ 决策留痕写入失败（不影响决策）: {}", e.getMessage());
        }
    }

    /** 阶段 3：把命中规则写入流水表（供命中率 / 误杀率统计） */
    private void recordHits(RiskDecisionRequest request, RiskRuleEngine.RuleOutcome outcome) {
        if (outcome == null || !outcome.hasHit()) {
            return;
        }
        for (RiskRuleEngine.RuleHit hit : outcome.hits()) {
            try {
                com.loan.entity.RiskRuleHitLog entity = new com.loan.entity.RiskRuleHitLog();
                entity.setApplicationId(request.getApplicationId());
                entity.setUserId(request.getUserId());
                entity.setRuleId(hit.ruleId());
                entity.setRuleCode(hit.ruleCode());
                entity.setRuleName(hit.name());
                entity.setLayer(hit.layer());
                entity.setAction(hit.action());
                entity.setShadow(hit.shadow());
                riskRuleHitLogMapper.insert(entity);
            } catch (Exception e) {
                log.warn("⚠️ 规则命中流水写入失败（不影响决策）: {}", e.getMessage());
            }
        }
    }

    private String buildReason(RiskRuleEngine.RuleOutcome l1, RiskRuleEngine.RuleOutcome l3) {
        StringBuilder sb = new StringBuilder("统一决策编排");
        if (l1 != null && l1.hasHit()) {
            sb.append("｜L1命中: ").append(l1.summary());
        }
        if (l3 != null && l3.hasHit()) {
            sb.append("｜L3命中: ").append(l3.summary());
        }
        return sb.toString();
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }
}

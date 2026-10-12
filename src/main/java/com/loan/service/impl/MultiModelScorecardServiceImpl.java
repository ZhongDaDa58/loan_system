package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.RiskDecisionLog;
import com.loan.entity.ShadowScoreLog;
import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.vo.MultiModelScoreResultVO;
import com.loan.exception.BusinessException;
import com.loan.mapper.RiskDecisionLogMapper;
import com.loan.scorecard.ModelRegistry;
import com.loan.scorecard.ModelScorer;
import com.loan.scorecard.model.ScorecardModel;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.MultiSourceService;
import com.loan.service.ShadowScoreService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 多模型评分服务实现（W4 · 主线 A；W5 扩展：影子采样 A5、引擎指标 A6、审批详情聚合 B2）
 *
 * 流程：解析模型（含**自动降级**）→ 通用取分 → 决策 → **决策留痕（含模型版本与特征快照）**
 *      → **影子记录（异步旁路，按 shadow.rate 采样）** → 指标记录 → 返回结果。
 */
@Service
public class MultiModelScorecardServiceImpl implements MultiModelScorecardService {

    private static final Logger log = LoggerFactory.getLogger(MultiModelScorecardServiceImpl.class);

    /** 影子对照的目标模型（离线回填时使用） */
    private static final String SHADOW_TARGET_MODEL = "v4-fusion";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Resource
    private ModelRegistry modelRegistry;

    @Resource
    private ModelScorer modelScorer;

    @Resource
    private ShadowScoreService shadowScoreService;

    @Resource
    private RiskDecisionLogMapper riskDecisionLogMapper;

    /** W5-A6：评分引擎指标（内存计数，供 /metrics 端点） */
    @Resource
    private com.loan.scorecard.ScoreMetrics scoreMetrics;

    /** W5-B2：审批详情聚合需要多源脱敏摘要 */
    @Resource
    private MultiSourceService multiSourceService;

    /**
     * W5-A5：影子评分采样比例（0=关闭，默认；1=全量；(0,1)=按比例采样）
     * 可通过 application.properties 或启动参数 --shadow.rate=1 临时打开（影子放量演练）。
     */
    @Value("${shadow.rate:0}")
    private double shadowRate;

    @Override
    public MultiModelScoreResultVO calculate(MultiModelScoreRequest request) {
        long startedAt = System.currentTimeMillis();
        String requested = request.getModelVersion();
        ScorecardModel model = modelRegistry.resolve(requested);
        if (model == null) {
            throw new BusinessException(500, "没有可用的评分卡模型，请检查 resources/models/ 配置");
        }
        boolean degraded = requested != null && !requested.isBlank()
                && !requested.equals(model.getModelKey());
        if (degraded) {
            log.warn("📉 评分请求发生降级：请求 {} → 实际使用 {}", requested, model.getModelKey());
        }

        ModelScorer.ScoreOutcome outcome = modelScorer.score(model, request.getFeatures());

        // A5：决策留痕（含模型版本 + 特征快照）
        writeDecisionLog(request, model, outcome);
        // A6：影子记录（异步，不影响返回；按 shadow.rate 采样）
        recordShadow(request, model, outcome);

        // W5-A6：记录引擎指标（次数/降级/耗时/版本分布）
        scoreMetrics.record(model.getModelKey(), degraded, System.currentTimeMillis() - startedAt);

        return MultiModelScoreResultVO.builder()
                .modelKey(model.getModelKey())
                .modelVersion(model.getVersion())
                .modelType(model.getModelType())
                .requestedVersion(requested)
                .degraded(degraded)
                .requiredFeatureCount(model.featureNames().size())
                .missingFeatures(outcome.missingFeatures())
                .totalScore(outcome.totalScore())
                .scoreLevel(outcome.scoreLevel())
                .decision(outcome.decision())
                .decisionDesc(outcome.decisionDesc())
                .featureScores(outcome.featureScores())
                .scoredAt(new Date())
                .build();
    }

    @Override
    public List<Map<String, Object>> listModels() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (ScorecardModel model : modelRegistry.list()) {
            result.add(toView(model));
        }
        return result;
    }

    @Override
    public Map<String, Object> getModel(String modelKey) {
        ScorecardModel model = modelRegistry.get(modelKey);
        return model == null ? null : toView(model);
    }

    /**
     * W5-B2：审批详情聚合
     * 数据来源：risk_decision_log（模型版本/分数/决策/特征贡献/特征快照）+ 多源脱敏摘要。
     */
    @Override
    public Map<String, Object> approvalSummary(String applicationId) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applicationId", applicationId);
        try {
            RiskDecisionLog decisionLog = riskDecisionLogMapper.selectByApplicationId(applicationId);
            if (decisionLog == null) {
                result.put("found", false);
                result.put("message", "未找到该申请的决策留痕（可能未走多模型评分接口）");
                return result;
            }

            result.put("found", true);
            result.put("userId", decisionLog.getUserId());
            result.put("modelVersion", decisionLog.getModelVersion());
            result.put("scorecardScore", decisionLog.getScorecardScore());
            result.put("scorecardDecision", decisionLog.getScorecardDecision());
            result.put("decisionReason", decisionLog.getDecisionReason());
            result.put("decisionTime", decisionLog.getDecisionTime());
            result.put("featureContributions", parseJson(decisionLog.getFeatureDetails()));
            result.put("featureSnapshot", parseJson(decisionLog.getFeatureSnapshot()));
            result.put("validationErrors", decisionLog.getValidationErrors());

            if (decisionLog.getUserId() != null && !decisionLog.getUserId().isBlank()) {
                result.put("multiSource", multiSourceService.getProfile(decisionLog.getUserId()).getData());
            }
        } catch (Exception e) {
            log.warn("⚠️ 审批详情聚合失败: applicationId={}, reason={}", applicationId, e.getMessage());
            result.put("found", false);
            result.put("message", "聚合失败：" + e.getMessage());
        }
        return result;
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> toView(ScorecardModel model) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("modelKey", model.getModelKey());
        view.put("version", model.getVersion());
        view.put("type", model.getModelType());
        view.put("name", model.getName());
        view.put("status", model.getStatus());
        view.put("sourceFile", model.getSourceFile());
        view.put("featureCount", model.getFeatures() == null ? 0 : model.getFeatures().size());
        view.put("thresholds", Map.of("autoApprove", model.autoApprove(), "manualReview", model.manualReview()));
        view.put("metrics", model.metrics());
        view.put("defaultModel", model.getModelKey().equals(modelRegistry.defaultKey()));
        if (model.getErrorMessage() != null) {
            view.put("errorMessage", model.getErrorMessage());
        }
        return view;
    }

    /** A5：写入决策留痕（含模型版本与特征快照） */
    private void writeDecisionLog(MultiModelScoreRequest request, ScorecardModel model,
                                  ModelScorer.ScoreOutcome outcome) {
        String applicationId = request.getApplicationId();
        if (applicationId == null || applicationId.isBlank()) {
            return; // 未关联申请单时不落留痕，避免污染业务数据
        }
        try {
            RiskDecisionLog entity = new RiskDecisionLog();
            entity.setApplicationId(applicationId);
            entity.setUserId(request.getUserId());
            entity.setScorecardScore(outcome.totalScore());
            entity.setScorecardDecision(outcome.decision());
            entity.setDecisionReason("多模型评分（模型 " + model.getModelKey() + "，版本 " + model.getVersion() + "）");
            entity.setFeatureDetails(toJson(outcome.featureScores()));
            entity.setModelVersion(model.getModelKey());
            entity.setFeatureSnapshot(toJson(request.getFeatures()));
            entity.setDecisionTime(new Date());
            entity.setCreateTime(new Date());
            riskDecisionLogMapper.insert(entity);
            log.info("📝 决策留痕已写入: applicationId={}, 模型={}, 分数={}",
                    applicationId, model.getModelKey(), outcome.totalScore());
        } catch (Exception e) {
            log.warn("⚠️ 决策留痕写入失败（不影响评分）: {}", e.getMessage());
        }
    }

    /** A6：异步记录影子任务（供批量回填影子分数）；W5-A5 增加采样控制 */
    private void recordShadow(MultiModelScoreRequest request, ScorecardModel model,
                              ModelScorer.ScoreOutcome outcome) {
        boolean hasAnchor = (request.getApplicationId() != null && !request.getApplicationId().isBlank())
                || (request.getUserId() != null && !request.getUserId().isBlank());
        if (!hasAnchor) {
            return;
        }
        if (!shouldSampleShadow()) {
            return; // A5：按 shadow.rate 采样；默认 0 表示关闭（不产生影子记录）
        }
        try {
            ShadowScoreLog shadow = new ShadowScoreLog();
            shadow.setApplicationId(request.getApplicationId());
            shadow.setUserId(request.getUserId());
            shadow.setPrimaryModelKey(model.getModelKey());
            shadow.setPrimaryScore(outcome.totalScore());
            shadow.setPrimaryDecision(outcome.decision());
            shadow.setFeaturesJson(toJson(request.getFeatures()));
            shadow.setShadowStatus("PENDING");
            shadow.setShadowModelKey(SHADOW_TARGET_MODEL);
            shadowScoreService.recordAsync(shadow);
        } catch (Exception e) {
            log.warn("⚠️ 影子记录提交失败（不影响评分）: {}", e.getMessage());
        }
    }

    /**
     * A5：影子采样判定
     * rate &lt;= 0 → 关闭；rate &gt;= 1 → 全量；否则按比例随机采样。
     */
    private boolean shouldSampleShadow() {
        if (shadowRate <= 0) {
            return false;
        }
        if (shadowRate >= 1) {
            return true;
        }
        return ThreadLocalRandom.current().nextDouble() < shadowRate;
    }

    private String toJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, Map.class);
        } catch (Exception e) {
            return null;
        }
    }
}

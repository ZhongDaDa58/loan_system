package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.ShadowScoreLog;
import com.loan.scorecard.ModelRegistry;
import com.loan.scorecard.ModelScorer;
import com.loan.scorecard.model.ScorecardModel;
import com.loan.service.ShadowBackfillService;
import com.loan.service.ShadowScoreService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 影子分数回填服务实现（W5-A4 · Java 侧方案）
 *
 * 流程：拉取 PENDING → 解析特征快照 → 选影子模型（避开主模型）→ 打分 → 回写 DONE/FAILED。
 * 说明：单条失败不影响其它记录；全程不抛异常给调用方。
 */
@Service
public class ShadowBackfillServiceImpl implements ShadowBackfillService {

    private static final Logger log = LoggerFactory.getLogger(ShadowBackfillServiceImpl.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** 影子对照优先使用的模型（主模型为 v4 时用 v3，反之用 v4） */
    private static final String FALLBACK_SHADOW_MODEL = "v3";

    @Resource
    private ShadowScoreService shadowScoreService;

    @Resource
    private ModelRegistry modelRegistry;

    @Resource
    private ModelScorer modelScorer;

    @Override
    public Map<String, Object> backfillPending(int limit) {
        int safeLimit = limit <= 0 ? 100 : Math.min(limit, 1000);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requested", safeLimit);

        List<ShadowScoreLog> pending = shadowScoreService.listPending(safeLimit);
        int done = 0;
        int failed = 0;

        if (pending != null) {
            for (ShadowScoreLog record : pending) {
                try {
                    Map<String, Object> features = parseFeatures(record.getFeaturesJson());
                    if (features == null) {
                        failed++;
                        shadowScoreService.fillShadowResult(record.getId(), null, null, null,
                                "特征快照解析失败，无法回填");
                        continue;
                    }

                    String shadowModelKey = chooseShadowModel(record.getPrimaryModelKey());
                    ScorecardModel model = modelRegistry.get(shadowModelKey);
                    if (model == null) {
                        failed++;
                        shadowScoreService.fillShadowResult(record.getId(), shadowModelKey, null, null,
                                "影子模型 " + shadowModelKey + " 不可用");
                        continue;
                    }

                    ModelScorer.ScoreOutcome outcome = modelScorer.score(model, features);
                    boolean ok = shadowScoreService.fillShadowResult(
                            record.getId(), model.getModelKey(),
                            outcome.totalScore(), outcome.decision(),
                            "Java 侧回填（W5-A4），主模型 " + record.getPrimaryModelKey());
                    if (ok) {
                        done++;
                    } else {
                        failed++;
                    }
                } catch (Exception e) {
                    failed++;
                    log.warn("⚠️ 影子回填单条失败: id={}, reason={}", record.getId(), e.getMessage());
                }
            }
        }

        result.put("done", done);
        result.put("failed", failed);
        result.put("pendingLeft", Math.max(0, (pending == null ? 0 : pending.size()) - done - failed));
        log.info("🕶️ 影子回填完成: 拉取={}, 成功={}, 失败={}", pending == null ? 0 : pending.size(), done, failed);
        return result;
    }

    // ==================== 内部方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseFeatures(String featuresJson) {
        if (featuresJson == null || featuresJson.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(featuresJson, Map.class);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 选择影子对照模型：优先与主模型不同（v4 ↔ v3），否则退回 v3 或默认模型。
     */
    private String chooseShadowModel(String primaryModelKey) {
        String preferred = "v4".equals(primaryModelKey) ? "v3" : "v4";
        if (modelRegistry.get(preferred) != null) {
            return preferred;
        }
        String defaultKey = modelRegistry.defaultKey();
        if (defaultKey != null && !defaultKey.equals(primaryModelKey)) {
            return defaultKey;
        }
        return FALLBACK_SHADOW_MODEL;
    }
}

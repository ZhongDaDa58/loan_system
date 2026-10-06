package com.loan.scorecard;

import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.entity.vo.MultiModelScoreResultVO;
import com.loan.scorecard.model.ScorecardModel;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.ScorecardService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 评分卡引擎多模型化测试（W4 · 主线 A7）
 *
 * 覆盖：模型注册表加载、版本路由、自动降级、与现网引擎的一致性、多源特征评分。
 */
@SpringBootTest
class ScorecardEngineTest {

    @Resource
    private ModelRegistry modelRegistry;

    @Resource
    private ModelScorer modelScorer;

    @Resource
    private MultiModelScorecardService multiModelScorecardService;

    /** 现网单模型服务（用于一致性对照） */
    @Resource
    private ScorecardService legacyScorecardService;

    @Test
    @DisplayName("注册表应加载 v3 与 v4（且 v4 为默认）")
    void registryShouldLoadModels() {
        assertTrue(modelRegistry.isAvailable("v3"), "v3 应可用");
        assertTrue(modelRegistry.isAvailable("v4"), "v4 应可用");

        ScorecardModel v3 = modelRegistry.get("v3");
        ScorecardModel v4 = modelRegistry.get("v4");
        assertEquals(6, v3.featureNames().size(), "v3 应为 6 特征");
        assertTrue(v4.featureNames().size() > 6, "v4 应包含多源特征");

        assertEquals("v4", modelRegistry.defaultKey(), "默认模型应为 v4");
        System.out.println("✅ 注册表就绪，可用模型：" + modelRegistry.list());
    }

    @Test
    @DisplayName("请求不存在的版本时应自动降级到默认模型")
    void unknownVersionShouldFallback() {
        ScorecardModel resolved = modelRegistry.resolve("v99-not-exist");
        assertNotNull(resolved, "应降级到可用模型而非返回空");
        assertEquals(modelRegistry.defaultKey(), resolved.getModelKey());
    }

    @Test
    @DisplayName("v3 模型打分应与现网引擎结果一致（算法口径不变）")
    void v3ScoringShouldMatchLegacyEngine() {
        UserProfileDTO profile = new UserProfileDTO();
        profile.setRevolvingUtil(new java.math.BigDecimal("0.35"));
        profile.setAge(42);
        profile.setDebtRatio(new java.math.BigDecimal("0.28"));
        profile.setMonthlyIncome(new java.math.BigDecimal("8500"));
        profile.setCreditLines(8);
        profile.setDependents(1);

        int legacyScore = legacyScorecardService.calculateScoreOnly(profile);

        Map<String, Object> features = new HashMap<>();
        features.put("revolving_util", 0.35);
        features.put("age", 42);
        features.put("debt_ratio", 0.28);
        features.put("monthly_income", 8500);
        features.put("credit_lines", 8);
        features.put("dependents", 1);

        ModelScorer.ScoreOutcome outcome = modelScorer.score(modelRegistry.get("v3"), features);

        System.out.printf("现网引擎分数=%d，多模型引擎(v3)分数=%d%n", legacyScore, outcome.totalScore());
        assertEquals(legacyScore, outcome.totalScore(), "两个引擎对 v3 的打分应完全一致");
    }

    @Test
    @DisplayName("v4 模型应能消费多源特征并给出决策")
    void v4ShouldScoreWithMultiSourceFeatures() {
        Map<String, Object> features = new HashMap<>();
        // 传统 6 特征
        features.put("revolving_util", 0.20);
        features.put("age", 45);
        features.put("debt_ratio", 0.25);
        features.put("monthly_income", 12000);
        features.put("credit_lines", 10);
        features.put("dependents", 1);
        // 多源特征（征信 / 运营商 / 社保 / 设备 / 行为）
        features.put("credit_query_cnt_3m", 1);
        features.put("credit_query_cnt_6m", 3);
        features.put("hist_overdue_cnt_m1", 0);
        features.put("hist_overdue_cnt_m2", 0);
        features.put("hist_overdue_cnt_m3", 0);
        features.put("operator_realname_months", 96);
        features.put("operator_tenure_months", 80);
        features.put("operator_pay_stability", 30);
        features.put("social_contrib_months", 120);
        features.put("social_base", 9000);
        features.put("social_employer_cnt", 1);
        features.put("device_related_app_cnt_30d", 1);
        features.put("device_first_seen_months", 24);
        features.put("gps_deviation_km_longterm", 12.0);
        features.put("behavior_anomaly_score", 0.2);

        ModelScorer.ScoreOutcome outcome = modelScorer.score(modelRegistry.get("v4"), features);

        assertTrue(outcome.totalScore() >= 300 && outcome.totalScore() <= 950, "分数应在 300-950 区间");
        assertNotNull(outcome.decision());
        assertTrue(outcome.missingFeatures().isEmpty(),
                "提供了全部多源特征时不应有缺失：" + outcome.missingFeatures());
        System.out.printf("v4 评分=%d，决策=%s，缺失特征=%s%n",
                outcome.totalScore(), outcome.decision(), outcome.missingFeatures());
    }

    @Test
    @DisplayName("多模型服务：未提供的多源特征应被标记为缺失（而非报错）")
    void missingMultiSourceFeaturesShouldBeReported() {
        MultiModelScoreRequest request = new MultiModelScoreRequest();
        Map<String, Object> features = new HashMap<>();
        features.put("revolving_util", 0.20);
        features.put("age", 45);
        features.put("debt_ratio", 0.25);
        features.put("monthly_income", 12000);
        features.put("credit_lines", 10);
        features.put("dependents", 1);
        request.setFeatures(features);
        request.setModelVersion("v4");

        MultiModelScoreResultVO result = multiModelScorecardService.calculate(request);

        assertEquals("v4", result.getModelKey());
        assertFalse(result.isDegraded(), "v4 可用时不应降级");
        assertFalse(result.getMissingFeatures().isEmpty(), "未传多源特征时应报告缺失");
        System.out.println("缺失特征数量：" + result.getMissingFeatures().size());
    }
}

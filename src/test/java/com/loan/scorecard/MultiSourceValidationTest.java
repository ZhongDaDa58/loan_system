package com.loan.scorecard;

import com.loan.service.DataValidationService;
import com.loan.service.impl.DataValidationServiceImpl;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * W5-A2 / A3：多源特征校验用例（类型 / 区间 / 业务规则）
 *
 * 说明：DataValidationServiceImpl 为纯逻辑实现（无外部依赖），直接实例化测试，
 *      不影响既有基于 UserBasicInfoDTO 的三层校验用例。
 */
class MultiSourceValidationTest {

    private final DataValidationService validationService = new DataValidationServiceImpl();

    @Test
    void validFeaturesShouldPass() {
        Map<String, Object> features = new LinkedHashMap<>();
        features.put("credit_query_cnt_3m", 1);
        features.put("credit_query_cnt_6m", 3);
        features.put("debt_to_income", 0.42);
        features.put("operator_realname_months", 85);
        features.put("operator_tenure_months", 74);
        features.put("operator_pay_stability", 36);
        features.put("device_related_app_cnt_30d", 2);
        features.put("behavior_anomaly_score", 0.2);
        features.put("gps_deviation_km_longterm", 12.0);

        List<String> errors = validationService.validateMultiSourceFeatures(features);
        assertTrue(errors.isEmpty(), "合法特征不应报错，实际：" + errors);
    }

    @Test
    void typeErrorShouldBeDetected() {
        Map<String, Object> features = new HashMap<>();
        features.put("credit_query_cnt_3m", "很多");

        List<String> errors = validationService.validateMultiSourceFeatures(features);
        assertFalse(errors.isEmpty());
        assertTrue(errors.stream().anyMatch(e -> e.contains("类型错误")), "应检出类型错误：" + errors);
    }

    @Test
    void outOfRangeShouldBeDetected() {
        Map<String, Object> features = new HashMap<>();
        features.put("behavior_anomaly_score", 1.7);

        List<String> errors = validationService.validateMultiSourceFeatures(features);
        assertTrue(errors.stream().anyMatch(e -> e.contains("超出合理区间")), "应检出区间越界：" + errors);
    }

    @Test
    void businessRuleConflictShouldBeDetected() {
        Map<String, Object> features = new HashMap<>();
        features.put("operator_realname_months", 10);
        features.put("operator_tenure_months", 50); // 在网时长 > 实名时长

        List<String> errors = validationService.validateMultiSourceFeatures(features);
        assertTrue(errors.stream().anyMatch(e -> e.contains("业务规则冲突")), "应检出业务规则冲突：" + errors);
    }

    @Test
    void emptyOrNullFeaturesShouldNotFail() {
        assertTrue(validationService.validateMultiSourceFeatures(new HashMap<>()).isEmpty());
        assertTrue(validationService.validateMultiSourceFeatures(null).isEmpty());
    }
}

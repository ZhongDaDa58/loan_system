package com.loan.rule;

import com.loan.entity.RiskRule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 风险规则引擎用例（阶段 1）
 *
 * 覆盖：AND/OR 语义、数值与字符串比较、字段别名、缺失值保守处理、
 *      REJECT 短路、影子规则不过滤动作、动作归并、字段白名单校验。
 */
class RiskRuleEngineTest {

    private final RiskRuleEngine engine = new RiskRuleEngine();

    private Map<String, Object> ctx(Object... kv) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return map;
    }

    private RiskRule rule(Long id, String code, String layer, String condition,
                          String action, int priority, boolean shadow) {
        RiskRule rule = new RiskRule();
        rule.setId(id);
        rule.setRuleCode(code);
        rule.setName(code);
        rule.setCategory("测试");
        rule.setLayer(layer);
        rule.setCondition(condition);
        rule.setAction(action);
        rule.setPriority(priority);
        rule.setEnabled(true);
        rule.setShadowMode(shadow);
        return rule;
    }

    @Test
    void andShouldRequireAllAtoms() {
        String condition = "device_related_app_cnt_30d >= 5 AND device_multi_account_now = 1";
        assertTrue(engine.matches(condition, ctx("device_related_app_cnt_30d", 5, "device_multi_account_now", 1)));
        assertFalse(engine.matches(condition, ctx("device_related_app_cnt_30d", 5, "device_multi_account_now", 0)));
        assertFalse(engine.matches(condition, ctx("device_related_app_cnt_30d", 4, "device_multi_account_now", 1)));
    }

    @Test
    void orShouldMatchAnyBranch() {
        String condition = "ip_proxy_risk >= 0.8 OR behavior_anomaly_score >= 0.8";
        assertTrue(engine.matches(condition, ctx("ip_proxy_risk", 0.9, "behavior_anomaly_score", 0.1)));
        assertTrue(engine.matches(condition, ctx("ip_proxy_risk", 0.1, "behavior_anomaly_score", 0.85)));
        assertFalse(engine.matches(condition, ctx("ip_proxy_risk", 0.1, "behavior_anomaly_score", 0.1)));
    }

    @Test
    void numericOperatorsShouldWork() {
        Map<String, Object> context = ctx("score", 700);
        assertTrue(engine.matches("score >= 643", context));
        assertTrue(engine.matches("score > 699", context));
        assertTrue(engine.matches("score <= 700", context));
        assertTrue(engine.matches("score = 700", context));
        assertTrue(engine.matches("score != 600", context));
        assertFalse(engine.matches("score < 700", context));
    }

    @Test
    void missingFieldShouldNotHit() {
        // 设备字段未同步（缺失）→ 不应命中，避免误杀
        assertFalse(engine.matches("device_emulator_flag = 1", ctx("score", 700)));
        assertFalse(engine.matches("device_emulator_flag = 0", ctx("score", 700)));
    }

    @Test
    void aliasShouldMapFrontendNames() {
        // 前端习惯用 creditScore / amount / multiLoan
        assertTrue(engine.matches("creditScore >= 643", ctx("score", 700)));
        assertTrue(engine.matches("amount >= 5000", ctx("applyAmount", 8000)));
        assertTrue(engine.matches("multiLoan >= 10", ctx("credit_query_cnt_3m", 12)));
    }

    @Test
    void rejectShouldShortCircuit() {
        List<RiskRule> rules = List.of(
                rule(1L, "L1-01", "L1", "device_emulator_flag = 1", "REJECT", 10, false),
                rule(2L, "L1-06", "L1", "credit_query_cnt_3m >= 15", "MANUAL", 15, false));
        RiskRuleEngine.RuleOutcome outcome = engine.evaluate(rules, ctx("device_emulator_flag", 1, "credit_query_cnt_3m", 20));
        assertEquals("REJECT", outcome.action());
        assertEquals(1, outcome.hits().size(), "命中 REJECT 后应短路，不再评估后续规则");
    }

    @Test
    void shadowRuleShouldNotAffectAction() {
        List<RiskRule> rules = List.of(
                rule(1L, "F-01", "L3", "device_first_seen_months < 3", "FLAG", 90, true));
        RiskRuleEngine.RuleOutcome outcome = engine.evaluate(rules, ctx("device_first_seen_months", 1));
        assertTrue(outcome.hasHit(), "影子规则应被记录");
        assertTrue(outcome.hits().get(0).shadow());
        assertNull(outcome.action(), "影子规则不应产生生效动作");
    }

    @Test
    void shouldMergeHighestActionRank() {
        List<RiskRule> rules = List.of(
                rule(1L, "L3-01", "L3", "score >= 643", "MANUAL", 30, false),
                rule(2L, "L3-02", "L3", "score >= 643", "DOWNGRADE", 31, false));
        RiskRuleEngine.RuleOutcome outcome = engine.evaluate(rules, ctx("score", 700));
        assertEquals("MANUAL", outcome.action(), "MANUAL 优先级高于 DOWNGRADE");
    }

    @Test
    void validateConditionShouldRejectUnknownFieldAndBadSyntax() {
        assertThrows(IllegalArgumentException.class, () -> engine.validateCondition("unknown_field >= 1"));
        assertThrows(IllegalArgumentException.class, () -> engine.validateCondition("score >= "));
        assertThrows(IllegalArgumentException.class, () -> engine.validateCondition("score 643"));
        engine.validateCondition("score >= 643 AND device_related_app_cnt_30d >= 3"); // 合法，不抛异常
    }

    @Test
    void chineseActionShouldBeNormalized() {
        List<RiskRule> rules = List.of(rule(1L, "X", "L3", "score >= 600", "降额", 10, false));
        assertEquals("DOWNGRADE", engine.evaluate(rules, ctx("score", 700)).action());
    }
}

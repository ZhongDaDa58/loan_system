package com.loan.rule;

import com.loan.entity.RiskRule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 风险规则引擎（阶段 1）
 *
 * 条件语法（受限表达式，**不使用脚本引擎**，规避注入风险）：
 *   atom   := field op value
 *   expr   := atom (AND atom)* (OR atom (AND atom)*)*
 *   op     := > | >= | < | <= | = | == | != | <>
 * 例：device_related_app_cnt_30d >= 5 AND device_multi_account_now = 1
 *
 * 语义：
 *  - 按规则 priority 升序执行；**REJECT 命中即短路**（同层后续规则不再评估）；
 *  - 影子规则（shadow_mode=true）只记录、不参与动作归并；
 *  - 字段缺失 → 该原子视为"不命中"（保守，避免误杀）。
 */
@Component
public class RiskRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleEngine.class);

    private static final Pattern ATOM = Pattern.compile(
            "^([A-Za-z_][A-Za-z0-9_]*)\\s*(>=|<=|!=|<>|==|=|>|<)\\s*([^=<>!\\s].*)$");

    /** 字段别名（前端/业务常用名 → 内部特征名） */
    private static final Map<String, String> FIELD_ALIAS = Map.of(
            "creditScore", "score",
            "amount", "applyAmount",
            "multiLoan", "credit_query_cnt_3m"
    );

    /** 允许出现在条件中的字段白名单 */
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "score", "applyAmount", "userId", "productId",
            // 征信
            "credit_query_cnt_3m", "credit_query_cnt_6m", "debt_to_income",
            "hist_overdue_cnt_m1", "hist_overdue_cnt_m2", "hist_overdue_cnt_m3",
            // 运营商
            "operator_realname_months", "operator_tenure_months", "operator_pay_stability",
            // 社保/公积金
            "social_contrib_months", "social_base", "social_employer_cnt",
            // 设备指纹/反欺诈
            "device_emulator_flag", "device_blacklist_hit_now", "device_multi_account_now",
            "device_blacklist_hit_cnt", "device_related_app_cnt_30d", "device_first_seen_months",
            // GPS / 行为 / IP
            "gps_deviation_km_longterm", "behavior_anomaly_score", "ip_proxy_risk", "ip_geo_mismatch",
            // 基础
            "revolving_util", "age", "monthly_income", "credit_lines", "dependents",
            // —— 第一层「信息校验先行」派生信号（由 L1SignalAssembler 计算，见开发文档 §4）——
            // 约定：算不出的信号给哨兵值 -1，并配 xxx_available 标志，供规则显式判断"算不出来"
            "liveness_passed", "liveness_available",
            "device_trusted", "device_available",
            "gps_addr_distance_km", "geo_available", "ip_geo_consistent",
            "behavior_available", "trust_level",
            // —— 特征工程层 §3.2 门禁产物（T3）：落选特征清单，供 L3 规则引用 ——
            // skipped_features 为逗号分隔的字段名（字符串比较用 = / !=）
            "skipped_feature_count", "skipped_features"
    );

    /** 动作优先级（归并时取最高） */
    private static final Map<String, Integer> ACTION_RANK = Map.of(
            "REJECT", 4, "MANUAL", 3, "DOWNGRADE", 2, "FLAG", 1);

    // ==================== 对外能力 ====================

    /**
     * 规则命中记录
     */
    public record RuleHit(Long ruleId, String ruleCode, String name, String layer,
                          String action, boolean shadow) {
    }

    /**
     * 评估结果
     *
     * @param action      归并后的最高优先级动作（REJECT / MANUAL / DOWNGRADE / FLAG）
     * @param actionParam 与 {@code action} 对应规则的动作参数
     *                    （P-025：DOWNGRADE 时存放**保留比例**，如 "0.6" = 只放 60%）
     */
    public record RuleOutcome(List<RuleHit> hits, String action, String actionParam, String summary) {
        public boolean hasHit() {
            return hits != null && !hits.isEmpty();
        }
    }

    /**
     * 按层评估规则集（短路 + 影子过滤 + 动作归并）
     */
    public RuleOutcome evaluate(List<RiskRule> rules, Map<String, Object> context) {
        List<RuleHit> hits = new ArrayList<>();
        String merged = null;
        String mergedParam = null;
        if (rules == null || rules.isEmpty()) {
            return new RuleOutcome(hits, null, null, null);
        }

        for (RiskRule rule : rules) {
            boolean hit;
            try {
                hit = matches(rule.getCondition(), context);
            } catch (Exception e) {
                log.warn("⚠️ 规则[{}]条件求值异常（跳过）: {}", rule.getRuleCode(), e.getMessage());
                continue;
            }
            if (!hit) {
                continue;
            }

            boolean shadow = Boolean.TRUE.equals(rule.getShadowMode());
            hits.add(new RuleHit(rule.getId(), rule.getRuleCode(), rule.getName(),
                    rule.getLayer(), rule.getAction(), shadow));

            if (shadow) {
                continue; // 影子：只记录，不影响动作
            }
            String action = normalizeAction(rule.getAction());
            if (merged == null || rank(action) > rank(merged)) {
                merged = action;
                mergedParam = rule.getActionParam();   // P-025：动作参数随最高优先级动作一起归并
            }
            if ("REJECT".equals(action)) {
                break; // 短路：命中拒绝即停止
            }
        }

        String summary = hits.isEmpty() ? null : summarize(hits);
        return new RuleOutcome(hits, merged, mergedParam, summary);
    }

    /**
     * 单条条件求值（供试算与引擎内部使用）
     */
    public boolean matches(String condition, Map<String, Object> context) {
        if (condition == null || condition.isBlank()) {
            return false;
        }
        Map<String, Object> ctx = context == null ? Map.of() : context;

        // OR of ANDs（不支持括号嵌套）
        for (String orPart : condition.split("(?i)\\s+OR\\s+")) {
            boolean allOfAnd = true;
            for (String atom : orPart.split("(?i)\\s+AND\\s+")) {
                if (!evalAtom(atom.trim(), ctx)) {
                    allOfAnd = false;
                    break;
                }
            }
            if (allOfAnd) {
                return true;
            }
        }
        return false;
    }

    /**
     * 条件合法性校验（保存规则时调用）：格式 + 字段白名单
     */
    public void validateCondition(String condition) {
        if (condition == null || condition.isBlank()) {
            throw new IllegalArgumentException("触发条件不能为空");
        }
        for (String orPart : condition.split("(?i)\\s+OR\\s+")) {
            for (String atom : orPart.split("(?i)\\s+AND\\s+")) {
                Matcher matcher = ATOM.matcher(atom.trim());
                if (!matcher.matches()) {
                    throw new IllegalArgumentException("条件格式非法：" + atom.trim()
                            + "（应为 field op value）");
                }
                String field = matcher.group(1);
                if (!ALLOWED_FIELDS.contains(field) && !FIELD_ALIAS.containsKey(field)) {
                    throw new IllegalArgumentException("条件使用了不支持的字段：" + field);
                }
            }
        }
    }

    // ==================== 内部实现 ====================

    private boolean evalAtom(String atom, Map<String, Object> context) {
        Matcher matcher = ATOM.matcher(atom);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("条件格式非法：" + atom);
        }
        String field = matcher.group(1);
        String op = matcher.group(2);
        String rawValue = matcher.group(3).trim();
        if (!ALLOWED_FIELDS.contains(field) && !FIELD_ALIAS.containsKey(field)) {
            throw new IllegalArgumentException("条件使用了不支持的字段：" + field);
        }

        String key = FIELD_ALIAS.getOrDefault(field, field);
        Object actual = context.get(key);
        if (actual == null) {
            return false; // 缺失视为不命中（保守）
        }

        Double left = toDouble(actual);
        Double right = toDouble(rawValue);
        if (left != null && right != null) {
            int cmp = Double.compare(left, right);
            switch (op) {
                case ">":
                    return cmp > 0;
                case ">=":
                    return cmp >= 0;
                case "<":
                    return cmp < 0;
                case "<=":
                    return cmp <= 0;
                case "=":
                case "==":
                    return cmp == 0;
                case "!=":
                case "<>":
                    return cmp != 0;
                default:
                    return false;
            }
        }

        String a = String.valueOf(actual);
        String b = stripQuotes(rawValue);
        switch (op) {
            case "=":
            case "==":
                return a.equals(b);
            case "!=":
            case "<>":
                return !a.equals(b);
            default:
                return false;
        }
    }

    /** 动作归一化：兼容中文写法 */
    private String normalizeAction(String action) {
        if (action == null) {
            return "FLAG";
        }
        String a = action.trim().toUpperCase();
        switch (a) {
            case "拒绝":
            case "REJECT":
                return "REJECT";
            case "转人工":
            case "人工":
            case "MANUAL":
                return "MANUAL";
            case "降额":
            case "DOWNGRADE":
                return "DOWNGRADE";
            case "标记":
            case "观察":
            case "FLAG":
                return "FLAG";
            default:
                return a;
        }
    }

    private int rank(String action) {
        return ACTION_RANK.getOrDefault(action, 0);
    }

    private String summarize(List<RuleHit> hits) {
        StringBuilder sb = new StringBuilder();
        for (RuleHit hit : hits) {
            if (sb.length() > 0) {
                sb.append("；");
            }
            sb.append(hit.ruleCode() == null ? hit.name() : hit.ruleCode())
                    .append("[").append(hit.action()).append(hit.shadow() ? "·影子" : "").append("]");
        }
        return sb.toString();
    }

    private Double toDouble(Object value) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value == null) {
            return null;
        }
        try {
            return Double.parseDouble(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stripQuotes(String raw) {
        String s = raw.trim();
        if (s.length() >= 2
                && ((s.startsWith("\"") && s.endsWith("\"")) || (s.startsWith("'") && s.endsWith("'")))) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /** 供管理端展示：字段白名单 */
    public Map<String, Object> allowedFieldsView() {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("fields", ALLOWED_FIELDS);
        view.put("aliases", FIELD_ALIAS);
        view.put("actions", ACTION_RANK.keySet());
        return view;
    }
}

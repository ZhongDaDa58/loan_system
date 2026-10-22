package com.loan.service.impl;

import java.math.BigDecimal;

/**
 * 规则决策的纯函数工具（阶段四 4.2 拆分类的第一步）
 *
 * <p><b>为什么抽它</b>：{@code LoanApplicationServiceImpl} 有 <b>1448 行</b>，
 * 其中大量是"无副作用、无 Mapper 依赖"的纯逻辑。这类代码最适合先抽出去 ——
 * 风险最低（不涉及事务、不涉及外部调用），而且能立刻让主类变薄。
 *
 * <p><b>抽取纪律</b>（见 `阶段四开发指导.md` §4.2.3）：
 * <ol>
 *   <li>一次只抽一组，抽完立刻跑 {@code mvnw test}</li>
 *   <li>先抽纯函数式部分，再抽有依赖的部分</li>
 *   <li>保持调用方语义不变</li>
 * </ol>
 *
 * <p>本类为**静态工具类**（无状态、无注入），因此调用方无需任何改动就能换成静态调用。
 */
public final class RuleDecisionSupport {

    private RuleDecisionSupport() {
    }

    /**
     * P-025：解析规则动作参数里的「保留比例」。
     *
     * <p>语义约定：{@code risk_rule.action_param} 存**保留比例**（如 "0.6" → 只放 60%）。
     * 同时兼容百分数写法（如 "60" → 0.6）。
     *
     * @return 合法则返回 (0,1] 的比例；非法或缺失返回 {@code null}（调用方按不降额处理）
     */
    public static BigDecimal parseRetainRatio(String actionParam) {
        if (actionParam == null || actionParam.isBlank()) {
            return null;
        }
        try {
            BigDecimal v = new BigDecimal(actionParam.trim());
            if (v.compareTo(BigDecimal.ONE) > 0) {
                // 兼容百分数写法：60 → 0.6
                v = v.divide(new BigDecimal("100"), 4, java.math.RoundingMode.HALF_UP);
            }
            return (v.signum() > 0 && v.compareTo(BigDecimal.ONE) <= 0) ? v : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 规则动作是否为"即时拒绝"（L1 短路判断用）。
     *
     * <p>第一层命中 REJECT 时**不进入评分**（设计文档 §2），所以这个判断
     * 决定的是"要不要短路整条链路"，必须大小写容错。
     */
    public static boolean isRejectAction(String action) {
        return action != null && "REJECT".equalsIgnoreCase(action.trim());
    }

    /**
     * 把规则命中的动作合并进评分卡决策。
     *
     * <p>优先级：<b>REJECT &gt; MANUAL/DOWNGRADE &gt; 评分卡原决策</b>；
     * FLAG 仅记录、不改变决策（返回原决策）。
     *
     * <p><b>为什么不返回更强的类型</b>：调用方传入的就是 {@code ScorecardResultEnum}，
     * 直接返回同类型可以让"归并"这件事串起来（L1 结果 → 评分卡结果 → L3 修正），
     * 不需要在中间来回转换。
     *
     * @param base      评分卡原始决策
     * @param ruleAction 规则动作（REJECT / MANUAL / DOWNGRADE / FLAG），null 或空表示不改变
     */
    public static com.loan.entity.enums.ScorecardResultEnum mergeRuleDecision(
            com.loan.entity.enums.ScorecardResultEnum base, String ruleAction) {
        if (ruleAction == null || ruleAction.isBlank()) {
            return base;
        }
        return switch (ruleAction.toUpperCase()) {
            case "REJECT" -> com.loan.entity.enums.ScorecardResultEnum.AUTO_REJECT;
            case "MANUAL", "DOWNGRADE" -> com.loan.entity.enums.ScorecardResultEnum.MANUAL_REVIEW;
            default -> base;
        };
    }

    /**
     * 把多源档案的两层 Map（category → 字段 → 值）扁平化进规则上下文。
     *
     * <p>规则引擎的条件按<b>字段名</b>取值（如 {@code device_count}），
     * 而多源数据是按 category 分块存的（credit / device / behavior …）。
     * 扁平化后规则就能直接引用内层字段，不用关心它属于哪个分类。
     */
    public static void flattenInto(java.util.Map<String, Object> target,
                                   java.util.Map<String, Object> blocks) {
        if (blocks == null) {
            return;
        }
        blocks.forEach((category, value) -> {
            if (value instanceof java.util.Map<?, ?> inner) {
                inner.forEach((k, v) -> {
                    if (k != null) {
                        target.put(String.valueOf(k), v);
                    }
                });
            } else if (value != null) {
                target.put(category, value);
            }
        });
    }
}

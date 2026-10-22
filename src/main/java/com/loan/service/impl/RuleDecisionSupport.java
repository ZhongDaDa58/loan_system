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
}

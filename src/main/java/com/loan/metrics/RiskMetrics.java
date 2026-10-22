package com.loan.metrics;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * 风控业务指标（阶段一 1.5）
 *
 * <p><b>设计原则</b>：把风控决策过程翻译成<b>可聚合的数字</b>，而不是记录明细。
 * 明细在 {@code risk_decision_log} / {@code risk_rule_hit_log} / 日志中；
 * 这里只做<b>计数与分布</b>，供 Prometheus 抓取、Grafana 展示、告警规则使用。
 *
 * <p><b>两条铁律</b>：
 * <ol>
 *   <li><b>标签值必须是有限的小集合</b>（decision 3 个 / rule_code 14 个 / signal 4 个 …）。
 *       绝不能把 {@code applicationId} / {@code userId} 之类高基数值做标签 —— 会导致
 *       Prometheus 时间序列爆炸。</li>
 *   <li><b>埋点必须在"决策最终确定之后"</b>。若埋在评分之后、规则归并之前，
 *       统计结果会与真实决策不一致（数字看起来正常，但实际是错的）。</li>
 * </ol>
 *
 * <p>指标命名遵循 Prometheus 惯例：{@code 域_对象_单位}，如 {@code loan_application_total}。
 */
@Component
public class RiskMetrics {

    private final MeterRegistry registry;

    public RiskMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 申请决策分布：AUTO_PASS / MANUAL_REVIEW / REJECT / AUTO_REJECT … */
    public void decision(String decision) {
        registry.counter("loan_application_total", "decision", safe(decision)).increment();
    }

    /**
     * 规则命中（含影子模式）。
     *
     * <p>用途：判断某条规则是否在生效、影子规则是否该转正。
     * {@code shadow=true} 的计数不应被当作"已拦截"。
     */
    public void ruleHit(String ruleCode, boolean shadow) {
        registry.counter("risk_rule_hit_total",
                "rule_code", safe(ruleCode),
                "shadow", String.valueOf(shadow)).increment();
    }

    /**
     * ★ 第一层信号缺失。
     *
     * <p>这是最贴合风控业务的一个指标：<b>数据采集出问题 = 风控在裸奔</b>。
     * 当 {@code signal="device"} 的计数突增，说明设备数据断了，
     * 此时所有用户都会被判定为"设备不可信"，进而触发转人工。
     */
    public void signalUnavailable(String signal) {
        registry.counter("l1_signal_unavailable_total", "signal", signal).increment();
    }

    /** 评分分布（用于漂移检测：分布左移 = 数据变化或模型失效） */
    public void score(String model, int score) {
        registry.summary("scorecard_score", "model", safe(model)).record(score);
    }

    /** 模型缺失特征数（缺失越多，评分越依赖 fill_value 兜底，可信度越低） */
    public void missingFeatures(String model, int count) {
        registry.summary("scorecard_missing_features", "model", safe(model)).record(count);
    }

    /** 降额触发（规则 DOWNGRADE 命中并实际降额） */
    public void downgrade() {
        registry.counter("loan_downgrade_total").increment();
    }

    /** 精细化定价档位命中（A/B/C/D/E） */
    public void pricingTier(String tier) {
        registry.counter("loan_pricing_tier_total", "tier", safe(tier)).increment();
    }

    /** 合同生成失败（原先只有 System.err 静默失败，现在可告警） */
    public void contractFailed() {
        registry.counter("contract_generate_failed_total").increment();
    }

    private static String safe(String s) {
        return (s == null || s.isBlank()) ? "unknown" : s;
    }
}

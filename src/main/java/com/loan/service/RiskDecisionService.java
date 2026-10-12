package com.loan.service;

import com.loan.entity.dto.RiskDecisionRequest;

import java.util.Map;

/**
 * 统一风险决策服务（阶段 2 · 决策编排）
 *
 * 编排顺序：**L1 规则（前置拦截）→ 特征装配 → 评分 → 产品阈值基准决策 → L3 规则（后置修正）→ 统一留痕**
 *
 * 设计取舍：
 *  - 作为**独立入口**提供，不替换既有申请链路（避免回归风险）；
 *  - L1 命中 REJECT 时**不进入评分**（安全前置）；
 *  - L3 只改处置动作（转人工/降额），**不改分数**（保持模型结果权威）。
 */
public interface RiskDecisionService {

    /**
     * 执行一次完整风险决策
     *
     * @return 含 score / thresholds / baseDecision / finalDecision / 命中规则 / 留痕结果
     */
    Map<String, Object> decide(RiskDecisionRequest request);
}

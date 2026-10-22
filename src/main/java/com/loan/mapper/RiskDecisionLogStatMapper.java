package com.loan.mapper;

import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 决策留痕统计 Mapper（模型使用评价：调用量 / 决策分布 / 分数分布 PSI）
 *
 * 数据来源：risk_decision_log（每笔决策都记录了实际使用的 model_version 与分数）。
 */
public interface RiskDecisionLogStatMapper {

    /** 按模型版本统计调用量与决策分布 */
    List<Map<String, Object>> countDecisionsByModelVersion();

    /** 按模型版本 + 分数桶统计（含"近 30 天 / 30 天前"两期计数，用于 PSI） */
    List<Map<String, Object>> countScoreBucketsByModelVersion(@Param("bucketSize") int bucketSize);

    /** 全部决策的分数桶分布（不区分模型） */
    List<Map<String, Object>> countAllScoreBuckets(@Param("bucketSize") int bucketSize);
}

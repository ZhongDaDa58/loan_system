package com.loan.mapper;

import com.loan.entity.RiskRuleHitLog;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 规则命中流水 Mapper（阶段 3）
 * 对应表：risk_rule_hit_log
 */
public interface RiskRuleHitLogMapper {

    /** 写入一条命中流水 */
    int insert(RiskRuleHitLog record);

    /** 总体命中概览 */
    Map<String, Object> selectOverview();

    /** 各规则命中统计 */
    List<Map<String, Object>> countByRule();

    /** 按层 × 动作统计 */
    List<Map<String, Object>> countByLayerAction();

    /** 命中规则的申请与最终审批结果（用于近似误杀率） */
    List<Map<String, Object>> countByRuleWithOutcome();

    /** 最近命中记录 */
    List<RiskRuleHitLog> selectRecent(@Param("limit") Integer limit);

    /** 某笔申请的命中记录（审批详情页用） */
    List<RiskRuleHitLog> selectByApplicationId(@Param("applicationId") String applicationId);
}

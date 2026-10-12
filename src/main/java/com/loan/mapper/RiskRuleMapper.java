package com.loan.mapper;

import com.loan.entity.RiskRule;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 风险规则 Mapper（阶段 1）
 * 对应表：risk_rule
 */
public interface RiskRuleMapper {

    /** 全部规则（管理端列表，可按分类过滤） */
    List<RiskRule> selectAll(@Param("category") String category);

    /** 按 ID 查询 */
    RiskRule selectById(@Param("id") Long id);

    /** 启用规则（按层级，供规则引擎加载） */
    List<RiskRule> selectEnabledByLayer(@Param("layer") String layer);

    /** 新增规则 */
    int insert(RiskRule rule);

    /** 更新规则 */
    int update(RiskRule rule);

    /** 删除规则 */
    int deleteById(@Param("id") Long id);

    /** 启用/停用 */
    int updateEnabled(@Param("id") Long id, @Param("enabled") Boolean enabled);
}

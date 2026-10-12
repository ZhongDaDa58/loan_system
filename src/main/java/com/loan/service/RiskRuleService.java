package com.loan.service;

import com.loan.entity.RiskRule;

import java.util.List;
import java.util.Map;

/**
 * 风险规则服务（阶段 1）
 *
 * 说明：当前实现**直查数据库**（规则量小），因此规则变更**天然热更新**；
 *      如需高频评估可后续加内存缓存 + 显式刷新。
 */
public interface RiskRuleService {

    /** 规则列表（可按分类过滤） */
    List<RiskRule> list(String category);

    /** 规则详情 */
    RiskRule getById(Long id);

    /** 新建规则（校验条件格式与字段白名单） */
    RiskRule create(RiskRule rule);

    /** 更新规则（校验条件格式与字段白名单） */
    RiskRule update(Long id, RiskRule rule);

    /** 删除规则 */
    boolean delete(Long id);

    /** 启用/停用 */
    boolean toggle(Long id, Boolean enabled);

    /** 切换影子模式 */
    boolean setShadow(Long id, Boolean shadow);

    /** 规则试算：给定特征上下文，返回命中规则与归并动作（不落库、不改状态） */
    Map<String, Object> evaluate(String layer, Map<String, Object> context);

    /** 字段白名单等元信息（供管理端提示） */
    Map<String, Object> metadata();

    /**
     * 规则命中统计（阶段 3）
     *
     * @param recentLimit 最近命中记录条数
     * @return 总体概览 / 各规则命中 / 层×动作分布 / 关联申请结果（近似误杀率）/ 最近命中
     */
    Map<String, Object> stats(int recentLimit);
}

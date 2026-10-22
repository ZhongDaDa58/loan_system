package com.loan.service.impl;

import com.loan.entity.RiskRule;
import com.loan.exception.BusinessException;
import com.loan.mapper.RiskRuleMapper;
import com.loan.rule.RiskRuleEngine;
import com.loan.service.RiskRuleService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 风险规则服务实现（阶段 1）
 */
@Service
public class RiskRuleServiceImpl implements RiskRuleService {

    private static final Logger log = LoggerFactory.getLogger(RiskRuleServiceImpl.class);

    /** 默认层级（未指定时按后置修正处理） */
    private static final String DEFAULT_LAYER = "L3";

    @Resource
    private RiskRuleMapper riskRuleMapper;

    @Resource
    private RiskRuleEngine riskRuleEngine;

    /** 阶段 3：规则命中流水（统计用） */
    @Resource
    private com.loan.mapper.RiskRuleHitLogMapper riskRuleHitLogMapper;

    @Override
    // 2.2b：规则列表每笔申请查一次全表、规则极少变更 → 缓存；写操作时主动清除
    // 依赖 RedisConfig 中显式定义的 RedisCacheManager（JSON 序列化）；
    // 否则 Spring Boot 默认用 JDK 序列化 → 实体未实现 Serializable → "Cannot serialize"
    @org.springframework.cache.annotation.Cacheable(
            value = "riskRules", key = "(#category == null) ? 'all' : #category")
    public List<RiskRule> list(String category) {
        return riskRuleMapper.selectAll(category);
    }

    @Override
    public RiskRule getById(Long id) {
        return riskRuleMapper.selectById(id);
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = "riskRules", allEntries = true)
    public RiskRule create(RiskRule rule) {
        validate(rule);
        if (rule.getLayer() == null || rule.getLayer().isBlank()) {
            rule.setLayer(DEFAULT_LAYER);
        }
        if (rule.getPriority() == null) {
            rule.setPriority(100);
        }
        if (rule.getEnabled() == null) {
            rule.setEnabled(false);
        }
        if (rule.getShadowMode() == null) {
            rule.setShadowMode(true); // 默认影子运行
        }
        riskRuleMapper.insert(rule);
        log.info("🆕 新增规则: {} [{}] {} → {}",
                rule.getName(), rule.getLayer(), rule.getCondition(), rule.getAction());
        return riskRuleMapper.selectById(rule.getId());
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = "riskRules", allEntries = true)
    public RiskRule update(Long id, RiskRule rule) {
        RiskRule existing = riskRuleMapper.selectById(id);
        if (existing == null) {
            throw new BusinessException(404, "规则不存在: " + id);
        }
        if (rule.getCondition() != null) {
            riskRuleEngine.validateCondition(rule.getCondition());
        }
        rule.setId(id);
        riskRuleMapper.update(rule);
        log.info("✏️ 更新规则: id={}", id);
        return riskRuleMapper.selectById(id);
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = "riskRules", allEntries = true)
    public boolean delete(Long id) {
        return riskRuleMapper.deleteById(id) > 0;
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = "riskRules", allEntries = true)
    public boolean toggle(Long id, Boolean enabled) {
        if (riskRuleMapper.selectById(id) == null) {
            throw new BusinessException(404, "规则不存在: " + id);
        }
        boolean target = Boolean.TRUE.equals(enabled);
        riskRuleMapper.updateEnabled(id, target);
        log.info("🔀 规则 {} 状态 → {}", id, target ? "启用" : "停用");
        return true;
    }

    @Override
    @org.springframework.cache.annotation.CacheEvict(value = "riskRules", allEntries = true)
    public boolean setShadow(Long id, Boolean shadow) {
        RiskRule rule = riskRuleMapper.selectById(id);
        if (rule == null) {
            throw new BusinessException(404, "规则不存在: " + id);
        }
        RiskRule update = new RiskRule();
        update.setId(id);
        update.setShadowMode(Boolean.TRUE.equals(shadow));
        riskRuleMapper.update(update);
        return true;
    }

    @Override
    public Map<String, Object> evaluate(String layer, Map<String, Object> context) {
        String targetLayer = (layer == null || layer.isBlank()) ? DEFAULT_LAYER : layer.toUpperCase();
        List<RiskRule> rules = riskRuleMapper.selectEnabledByLayer(targetLayer);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("layer", targetLayer);
        result.put("ruleCount", rules == null ? 0 : rules.size());

        RiskRuleEngine.RuleOutcome outcome = riskRuleEngine.evaluate(rules, context);
        List<Map<String, Object>> hits = new ArrayList<>();
        if (outcome.hits() != null) {
            for (RiskRuleEngine.RuleHit hit : outcome.hits()) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("ruleId", hit.ruleId());
                item.put("ruleCode", hit.ruleCode());
                item.put("name", hit.name());
                item.put("action", hit.action());
                item.put("shadow", hit.shadow());
                hits.add(item);
            }
        }
        result.put("hits", hits);
        result.put("action", outcome.action());
        result.put("hitCount", hits.size());
        if (hits.isEmpty()) {
            result.put("message", "未命中任何规则（注意：默认停用/影子状态的规则不会生效）");
        }
        return result;
    }

    @Override
    public Map<String, Object> metadata() {
        Map<String, Object> view = new LinkedHashMap<>(riskRuleEngine.allowedFieldsView());
        view.put("layers", List.of("L1", "L3"));
        view.put("note", "规则默认停用 + 影子模式；启用后按 priority 升序执行，REJECT 短路");
        return view;
    }

    @Override
    public Map<String, Object> stats(int recentLimit) {
        int limit = recentLimit <= 0 ? 20 : Math.min(recentLimit, 200);
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            result.put("overview", riskRuleHitLogMapper.selectOverview());
            result.put("byRule", riskRuleHitLogMapper.countByRule());
            result.put("byLayerAction", riskRuleHitLogMapper.countByLayerAction());
            result.put("outcome", riskRuleHitLogMapper.countByRuleWithOutcome());
            result.put("recent", riskRuleHitLogMapper.selectRecent(limit));
        } catch (Exception e) {
            log.warn("⚠️ 规则命中统计查询失败: {}", e.getMessage());
            result.put("error", e.getMessage());
        }
        result.put("note", "命中数=各规则触发次数；近似误杀率=命中且最终审批通过的申请占比（需申请单关联，独立决策入口无关联数据）");
        return result;
    }

    private void validate(RiskRule rule) {
        if (rule.getName() == null || rule.getName().isBlank()) {
            throw new BusinessException(400, "规则名称不能为空");
        }
        if (rule.getCategory() == null || rule.getCategory().isBlank()) {
            throw new BusinessException(400, "规则分类不能为空");
        }
        if (rule.getAction() == null || rule.getAction().isBlank()) {
            throw new BusinessException(400, "处理动作不能为空");
        }
        try {
            riskRuleEngine.validateCondition(rule.getCondition());
        } catch (IllegalArgumentException e) {
            throw new BusinessException(400, e.getMessage());
        }
        if (rule.getLayer() != null && !"L1".equals(rule.getLayer()) && !"L3".equals(rule.getLayer())) {
            throw new BusinessException(400, "层级只能是 L1 或 L3");
        }
    }
}

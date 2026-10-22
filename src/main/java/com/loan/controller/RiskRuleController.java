package com.loan.controller;

import com.loan.entity.RiskRule;
import com.loan.entity.vo.Result;
import com.loan.service.RiskRuleService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 风险规则管理接口（阶段 1）
 *
 * 路径与 Web 端既有调用保持一致（`/api/v1/risk/rules`），前端无需改动即可接通。
 */
@RestController
@RequestMapping("/api/v1/risk/rules")
@Tag(name = "风险规则模块", description = "规则 CRUD、启停、影子开关与规则试算（阶段 1）")
public class RiskRuleController {

    @Resource
    private RiskRuleService riskRuleService;

    /** 规则命中流水（审批详情页：查询某笔申请命中的规则） */
    @Resource
    private com.loan.mapper.RiskRuleHitLogMapper riskRuleHitLogMapper;

    @GetMapping("/hits/{applicationId}")
    @Operation(summary = "规则命中查询",
            description = "某笔申请的规则命中明细 + 本次评估的启用规则数（审批详情页：回答“是否触及规则”）")
    public Result<?> hits(@PathVariable("applicationId") String applicationId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("applicationId", applicationId);

        java.util.List<com.loan.entity.RiskRuleHitLog> hitList =
                riskRuleHitLogMapper.selectByApplicationId(applicationId);
        data.put("hits", hitList);
        data.put("hitCount", hitList.size());

        java.util.List<com.loan.entity.RiskRule> enabled = riskRuleService.list(null).stream()
                .filter(r -> Boolean.TRUE.equals(r.getEnabled()))
                .toList();
        data.put("evaluated", true);
        data.put("enabledRuleCount", enabled.size());
        data.put("enabledRules", enabled.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ruleCode", r.getRuleCode());
            m.put("name", r.getName());
            m.put("layer", r.getLayer());
            m.put("action", r.getAction());
            m.put("shadowMode", r.getShadowMode());
            return m;
        }).toList());
        return Result.success(data);
    }

    @GetMapping
    @Operation(summary = "规则列表", description = "可按分类过滤；按优先级升序返回")
    public Result<?> list(@RequestParam(value = "category", required = false) String category) {
        return Result.success(riskRuleService.list(category));
    }

    @GetMapping("/metadata")
    @Operation(summary = "规则元信息", description = "字段白名单、别名映射、可选动作（供管理端提示）")
    public Result<?> metadata() {
        return Result.success(riskRuleService.metadata());
    }

    @PostMapping
    @Operation(summary = "新建规则", description = "条件为受限表达式（field op value [AND|OR ...]），服务端校验字段白名单")
    public Result<?> create(@RequestBody RiskRule rule) {
        return Result.success(riskRuleService.create(rule));
    }

    @PutMapping("/{id}")
    @Operation(summary = "更新规则")
    public Result<?> update(@PathVariable("id") Long id, @RequestBody RiskRule rule) {
        return Result.success(riskRuleService.update(id, rule));
    }

    @DeleteMapping("/{id}")
    @Operation(summary = "删除规则")
    public Result<?> delete(@PathVariable("id") Long id) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deleted", riskRuleService.delete(id));
        return Result.success(data);
    }

    @PatchMapping("/{id}/toggle")
    @Operation(summary = "启用/停用规则", description = "请求体 {\"enabled\": true}")
    public Result<?> toggle(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Object value = body == null ? null : body.get("enabled");
        boolean enabled = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        riskRuleService.toggle(id, enabled);
        return Result.success(riskRuleService.getById(id));
    }

    @PatchMapping("/{id}/shadow")
    @Operation(summary = "切换影子模式", description = "请求体 {\"shadow\": true}；影子规则只记录不生效")
    public Result<?> shadow(@PathVariable("id") Long id, @RequestBody Map<String, Object> body) {
        Object value = body == null ? null : body.get("shadow");
        boolean shadow = value instanceof Boolean b ? b : Boolean.parseBoolean(String.valueOf(value));
        riskRuleService.setShadow(id, shadow);
        return Result.success(riskRuleService.getById(id));
    }

    @PostMapping("/evaluate")
    @Operation(summary = "规则试算",
            description = "请求体 {\"layer\":\"L1|L3\", \"context\": {\"score\": 700, \"device_emulator_flag\": 1}}；只计算不落库")
    public Result<?> evaluate(@RequestBody Map<String, Object> body) {
        String layer = body == null ? null : (String) body.get("layer");
        Object contextObj = body == null ? null : body.get("context");
        @SuppressWarnings("unchecked")
        Map<String, Object> context = contextObj instanceof Map ? (Map<String, Object>) contextObj : new LinkedHashMap<>();
        return Result.success(riskRuleService.evaluate(layer, context));
    }

    @GetMapping("/stats")
    @Operation(summary = "规则命中统计（阶段 3）",
            description = "总体概览、各规则命中次数、层级×动作分布、关联申请审批结果（近似误杀率）、最近命中记录")
    public Result<?> stats(@RequestParam(value = "recentLimit", defaultValue = "20") int recentLimit) {
        return Result.success(riskRuleService.stats(recentLimit));
    }
}

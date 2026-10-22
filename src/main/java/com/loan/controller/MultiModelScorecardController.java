package com.loan.controller;

import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.vo.Result;
import com.loan.scorecard.ScoreMetrics;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.ShadowBackfillService;
import com.loan.service.ShadowScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 多模型评分接口（W4 主线 A；W5 扩展：影子回填 A4、引擎指标 A6）
 *
 * 与既有 {@code ScorecardController}（/api/v1/scorecard 的 4 个端点）并存，
 * **不改动**既有端点与行为；本控制器提供：
 *   - 按模型版本评分（支持多源特征、版本路由与自动降级）
 *   - 模型注册表查询
 *   - 影子记录查询与**影子分数回填**（旁路对照）
 *   - **评分引擎指标**（评分次数/降级次数/耗时/版本分布 + 影子进度）
 */
@RestController
@RequestMapping("/api/v1/scorecard/v2")
@Tag(name = "评分卡多模型模块", description = "模型版本路由、自动降级、影子对照与回填、引擎指标（W4/W5）")
public class MultiModelScorecardController {

    @Resource
    private MultiModelScorecardService multiModelScorecardService;

    @Resource
    private ShadowScoreService shadowScoreService;

    @Resource
    private ShadowBackfillService shadowBackfillService;

    @Resource
    private ScoreMetrics scoreMetrics;

    /** 模型质量评估（模型配置页：IV / PSI / 使用量） */
    @Resource
    private com.loan.scorecard.ModelQualityService modelQualityService;

    @PostMapping("/calculate")
    @Operation(summary = "多模型评分", description = "按指定版本（或默认版本）评分；请求版本不可用时自动降级，支持多源特征")
    public Result<?> calculate(@Valid @RequestBody MultiModelScoreRequest request) {
        return Result.success(multiModelScorecardService.calculate(request));
    }

    @GetMapping("/models")
    @Operation(summary = "模型注册表", description = "列出已注册的评分卡模型（版本/状态/特征数/指标/是否默认）")
    public Result<?> listModels() {
        return Result.success(multiModelScorecardService.listModels());
    }

    @GetMapping("/models/{modelKey}")
    @Operation(summary = "模型详情", description = "按注册键查询单个模型（如 v3 / v4 / v4-fusion）")
    public Result<?> getModel(@PathVariable("modelKey") String modelKey) {
        var model = multiModelScorecardService.getModel(modelKey);
        if (model == null) {
            return Result.error(404, "模型不存在: " + modelKey);
        }
        return Result.success(model);
    }

    @GetMapping("/shadow")
    @Operation(summary = "影子记录查询", description = "查看最近的影子评分记录与各状态统计（旁路对照）")
    public Result<?> shadow(@RequestParam(value = "limit", defaultValue = "20") int limit) {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("stats", shadowScoreService.stats());
        response.put("recent", shadowScoreService.listRecent(limit));
        response.put("pending", shadowScoreService.listPending(limit));
        return Result.success(response);
    }

    @PostMapping("/shadow/backfill")
    @Operation(summary = "影子分数回填",
            description = "批量回填 PENDING 影子记录（Java 侧用 LR 模型打分，目标模型自动避开主模型；W5-A4）")
    public Result<?> backfillShadow(@RequestParam(value = "limit", defaultValue = "100") int limit) {
        return Result.success(shadowBackfillService.backfillPending(limit));
    }

    @GetMapping("/metrics")
    @Operation(summary = "评分引擎指标",
            description = "评分次数/降级次数/平均耗时/版本分布（engine）+ 影子各状态统计（shadow）；W5-A6")
    public Result<?> metrics() {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("engine", scoreMetrics.snapshot());
        response.put("shadow", shadowScoreService.stats());
        return Result.success(response);
    }

    @GetMapping("/approval-summary")
    @Operation(summary = "审批详情聚合",
            description = "按申请 ID 聚合决策留痕（模型版本/分数/决策/特征贡献/特征快照）+ 多源脱敏摘要；W5-B2，供 Web 审批详情页")
    public Result<?> approvalSummary(@RequestParam("applicationId") String applicationId) {
        return Result.success(multiModelScorecardService.approvalSummary(applicationId));
    }

    @GetMapping("/quality/overview")
    @Operation(summary = "模型质量总览（模型配置页）",
            description = "模型清单 + 默认模型 + 调用量/决策分布 + IV 汇总 + PSI（近 30 天 vs 30 天前）")
    public Result<?> qualityOverview() {
        return Result.success(modelQualityService.overview());
    }

    @GetMapping("/quality/{modelKey}/features")
    @Operation(summary = "模型特征级质量",
            description = "逐特征 IV / 分箱数 / 来源 / 系数（供模型配置页下钻查看）")
    public Result<?> modelFeatureQuality(@PathVariable("modelKey") String modelKey) {
        var quality = modelQualityService.featureQuality(modelKey);
        if (quality == null) {
            return Result.error(404, "模型不存在: " + modelKey);
        }
        return Result.success(quality);
    }
}

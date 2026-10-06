package com.loan.controller;

import com.loan.entity.dto.MultiModelScoreRequest;
import com.loan.entity.vo.Result;
import com.loan.service.MultiModelScorecardService;
import com.loan.service.ShadowScoreService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * 多模型评分接口（W4 · 主线 A）
 *
 * 与既有 {@code ScorecardController}（/api/v1/scorecard 的 4 个端点）并存，
 * **不改动**既有端点与行为；本控制器提供：
 *   - 按模型版本评分（支持多源特征、版本路由与自动降级）
 *   - 模型注册表查询
 *   - 影子记录查询（旁路对照）
 */
@RestController
@RequestMapping("/api/v1/scorecard/v2")
@Tag(name = "评分卡多模型模块", description = "模型版本路由、自动降级、影子对照（W4）")
public class MultiModelScorecardController {

    @Resource
    private MultiModelScorecardService multiModelScorecardService;

    @Resource
    private ShadowScoreService shadowScoreService;

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
}

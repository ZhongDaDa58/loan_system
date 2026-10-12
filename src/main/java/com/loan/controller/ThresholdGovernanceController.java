package com.loan.controller;

import com.loan.entity.ProductThresholdSuggestion;
import com.loan.entity.vo.Result;
import com.loan.service.ThresholdGovernanceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阈值治理接口（P0）
 *
 * 打通"离线标定 → 产品配置"链路：
 *   - 导入标定建议（供 calibrate_by_product.py 产出回传）
 *   - 查询"当前阈值 vs 建议阈值"对比
 *   - 采纳建议（写入产品配置 + 记录变更历史）
 */
@RestController
@RequestMapping("/api/v1/threshold")
@Tag(name = "阈值治理模块", description = "标定建议导入/查询/采纳（P0）")
public class ThresholdGovernanceController {

    @Resource
    private ThresholdGovernanceService thresholdGovernanceService;

    @PostMapping("/suggestions/import")
    @Operation(summary = "导入标定建议",
            description = "批量导入离线标定产出（scorecard_project/scripts/calibrate_by_product.py）的建议阈值")
    public Result<?> importSuggestions(@RequestBody List<ProductThresholdSuggestion> suggestions) {
        int imported = thresholdGovernanceService.importSuggestions(suggestions);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("imported", imported);
        return Result.success(data);
    }

    @GetMapping("/{productId}/suggestion")
    @Operation(summary = "查询标定建议", description = "返回该产品当前阈值与最新标定建议的对比（含差值）")
    public Result<?> getSuggestion(@PathVariable("productId") String productId) {
        Map<String, Object> result = thresholdGovernanceService.getSuggestion(productId);
        if (result == null) {
            return Result.error(404, "产品不存在: " + productId);
        }
        return Result.success(result);
    }

    @PostMapping("/{productId}/suggestion/adopt")
    @Operation(summary = "采纳标定建议",
            description = "将建议阈值写入产品配置，并记录变更历史（threshold_source=CALIBRATED）")
    public Result<?> adoptSuggestion(@PathVariable("productId") String productId,
                                     @RequestParam(value = "reason", required = false) String reason) {
        Map<String, Object> result = thresholdGovernanceService.adoptSuggestion(productId, reason);
        if (result == null) {
            return Result.error(404, "产品不存在或暂无标定建议: " + productId);
        }
        return Result.success(result);
    }
}

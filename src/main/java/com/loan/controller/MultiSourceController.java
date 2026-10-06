package com.loan.controller;

import com.loan.entity.dto.MultiSourceEventDTO;
import com.loan.entity.vo.Result;
import com.loan.service.MultiSourceIngestService;
import com.loan.service.MultiSourceService;
import com.loan.service.MultiSourceSyncService;
import com.loan.spi.DataSourceRegistry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 多源数据模块（摘要展示 + 事件上报 + 同步调度）
 * W4 主线 B 新增：批量/增量同步、同步统计、数据源注册表（开关与熔断状态）。
 */
@RestController
@RequestMapping("/api/v1/multi-source")
@Tag(name = "多源数据模块", description = "多源数据摘要、事件上报、同步调度与数据源注册表")
public class MultiSourceController {

    @Resource
    private MultiSourceService multiSourceService;

    @Resource
    private MultiSourceIngestService multiSourceIngestService;

    @Resource
    private MultiSourceSyncService multiSourceSyncService;

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    @GetMapping("/profile")
    @Operation(summary = "多源数据脱敏摘要", description = "返回各数据源授权状态与脱敏数据块（结构先行，数据块待 W5 填充）")
    public Result<?> getProfile(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return multiSourceService.getProfile(userId);
    }

    @PostMapping("/event")
    @Operation(summary = "客户端事件上报", description = "App 端设备/行为事件上报入口（预留：本期仅校验与记录日志）")
    public Result<?> reportEvents(@Valid @RequestBody List<MultiSourceEventDTO> events,
                                  HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return multiSourceService.reportEvents(userId, events);
    }

    @PostMapping("/sync")
    @Operation(summary = "同步多源数据", description = "通过 SPI 拉取指定数据源（或全部已注册源），标准化清洗后落库 user_multi_source_data")
    public Result<?> sync(@RequestParam(value = "sourceType", required = false) String sourceType,
                          HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return Result.success(multiSourceIngestService.sync(userId, sourceType));
    }

    @GetMapping("/features")
    @Operation(summary = "多源特征宽表快照", description = "读取已落库的多源数据，合并为特征宽表（供评分卡与展示使用）")
    public Result<?> features(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        return Result.success(multiSourceIngestService.featureSnapshot(userId));
    }

    // ==================== W4 主线 B：同步调度与数据源注册表 ====================

    @PostMapping("/sync/batch")
    @Operation(summary = "批量同步多源数据", description = "对【已授权】用户批量拉取（拉取→清洗→落库）；sourceType 为空则处理全部已注册源")
    public Result<?> syncBatch(@RequestParam(value = "sourceType", required = false) String sourceType,
                               @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        return Result.success(multiSourceSyncService.syncBatch(sourceType, limit));
    }

    @PostMapping("/sync/incremental")
    @Operation(summary = "增量同步多源数据", description = "只同步【已授权但数据缺失或已过期】的用户；staleHours 为过期阈值（默认 24 小时）")
    public Result<?> syncIncremental(@RequestParam(value = "sourceType", required = false) String sourceType,
                                     @RequestParam(value = "staleHours", defaultValue = "24") Integer staleHours,
                                     @RequestParam(value = "limit", defaultValue = "200") Integer limit) {
        return Result.success(multiSourceSyncService.syncIncremental(sourceType, staleHours, limit));
    }

    @GetMapping("/sync/stats")
    @Operation(summary = "最近一次同步统计", description = "返回最近一次批量/增量同步的统计（用户数、成功/失败、各源明细、耗时、熔断状态）")
    public Result<?> syncStats() {
        return Result.success(multiSourceSyncService.lastRunStats());
    }

    @GetMapping("/registry")
    @Operation(summary = "数据源注册表", description = "各数据源的开关、优先级、熔断状态与健康检查结果（统一管理与调度）")
    public Result<?> registry() {
        var response = new java.util.LinkedHashMap<String, Object>();
        response.put("sources", dataSourceRegistry.listConfigs());
        response.put("health", dataSourceRegistry.healthCheckAll());
        return Result.success(response);
    }
}

package com.loan.service.impl;

import com.loan.mapper.MultiSourceSyncMapper;
import com.loan.service.MultiSourceIngestService;
import com.loan.service.MultiSourceSyncService;
import com.loan.spi.DataSourceRegistry;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源数据同步调度服务实现（W4 · 主线 B4/B5）
 *
 * 设计要点：
 *  1) **只同步已授权用户**（范围来自 `data_source_authorization`，合规优先）；
 *  2) 批量与增量复用 {@link MultiSourceIngestService#sync}（拉取→清洗→落库），避免逻辑重复；
 *  3) 定时任务默认**关闭**（`multisource.sync.enabled=false`），避免影响线上；演示/生产按需开启；
 *  4) 每次运行记录统计（总数/成功/失败/空/耗时），供可观测与评审查看。
 */
@Service
public class MultiSourceSyncServiceImpl implements MultiSourceSyncService {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceSyncServiceImpl.class);

    private static final int DEFAULT_LIMIT = 200;
    private static final int DEFAULT_STALE_HOURS = 24;

    @Resource
    private MultiSourceSyncMapper syncMapper;

    @Resource
    private MultiSourceIngestService ingestService;

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    /** 定时同步开关（默认关闭，需显式开启） */
    @Value("${multisource.sync.enabled:false}")
    private boolean scheduledEnabled;

    /** 增量同步默认"过期"阈值（小时） */
    @Value("${multisource.sync.stale-hours:24}")
    private int scheduledStaleHours;

    /** 最近一次运行统计 */
    private volatile Map<String, Object> lastRun = new LinkedHashMap<>();

    @Override
    public Map<String, Object> syncBatch(String sourceType, Integer limit) {
        return run("BATCH", sourceType, null, limit == null ? DEFAULT_LIMIT : limit);
    }

    @Override
    public Map<String, Object> syncIncremental(String sourceType, Integer staleHours, Integer limit) {
        return run("INCREMENTAL", sourceType,
                staleHours == null ? DEFAULT_STALE_HOURS : staleHours,
                limit == null ? DEFAULT_LIMIT : limit);
    }

    @Override
    public Map<String, Object> lastRunStats() {
        return lastRun;
    }

    /**
     * 定时同步（每日 02:30，避开既有逾期检测 02:00）
     * 开关：multisource.sync.enabled（默认 false）
     */
    @Scheduled(cron = "${multisource.sync.cron:0 30 2 * * ?}")
    public void scheduledSync() {
        if (!scheduledEnabled) {
            log.debug("ℹ️ 多源定时同步未开启（multisource.sync.enabled=false），跳过");
            return;
        }
        log.info("⏰ 多源定时同步开始（增量模式，过期阈值 {} 小时）", scheduledStaleHours);
        Map<String, Object> stats = syncIncremental(null, scheduledStaleHours, DEFAULT_LIMIT);
        log.info("⏰ 多源定时同步结束：{}", stats);
    }

    // ==================== 内部实现 ====================

    private Map<String, Object> run(String mode, String sourceType, Integer staleHours, int limit) {
        long start = System.currentTimeMillis();
        List<String> sources = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            sources.add(sourceType.trim().toLowerCase());
        } else {
            sources.addAll(dataSourceRegistry.listSourceTypes());
        }

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("mode", mode);
        stats.put("sources", sources);
        stats.put("limit", limit);
        stats.put("startedAt", new Date(start));

        int totalUsers = 0;
        int success = 0;
        int failed = 0;
        List<Map<String, Object>> perSource = new ArrayList<>();

        for (String source : sources) {
            List<String> users;
            try {
                if ("INCREMENTAL".equals(mode)) {
                    Date threshold = new Date(System.currentTimeMillis()
                            - staleHours * 3600_000L);
                    users = syncMapper.selectStaleAuthorizedUsers(source, threshold, limit);
                } else {
                    users = syncMapper.selectAuthorizedUsers(source, limit);
                }
            } catch (Exception e) {
                log.error("❌ 查询待同步用户失败: source={}, reason={}", source, e.getMessage());
                users = List.of();
            }

            int sourceSuccess = 0;
            int sourceFailed = 0;
            for (String userId : users) {
                totalUsers++;
                try {
                    Map<String, Object> result = ingestService.sync(userId, source);
                    Object status = extractStatus(result, source);
                    if ("SUCCESS".equals(status)) {
                        success++;
                        sourceSuccess++;
                    } else if ("EMPTY".equals(status)) {
                        // 数据源被停用/无数据：计入失败但单独标注
                        sourceFailed++;
                        failed++;
                    } else {
                        failed++;
                        sourceFailed++;
                    }
                } catch (Exception e) {
                    failed++;
                    sourceFailed++;
                    log.warn("⚠️ 用户 {} 源 {} 同步异常: {}", userId, source, e.getMessage());
                }
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceType", source);
            row.put("users", users.size());
            row.put("success", sourceSuccess);
            row.put("failed", sourceFailed);
            perSource.add(row);
            log.info("📊 [{}] 源 {}：待同步 {} 人，成功 {}，失败 {}",
                    mode, source, users.size(), sourceSuccess, sourceFailed);
        }

        long elapsed = System.currentTimeMillis() - start;
        stats.put("users", totalUsers);
        stats.put("success", success);
        stats.put("failed", failed);
        stats.put("perSource", perSource);
        stats.put("elapsedMs", elapsed);
        stats.put("finishedAt", new Date());
        stats.put("circuit", dataSourceRegistry.listConfigs());

        lastRun = stats;
        log.info("✅ 多源同步完成（{}）：用户 {}，成功 {}，失败 {}，耗时 {} ms",
                mode, totalUsers, success, failed, elapsed);
        return stats;
    }

    @SuppressWarnings("unchecked")
    private String extractStatus(Map<String, Object> result, String source) {
        if (result == null) {
            return "FAILED";
        }
        Object one = result.get(source);
        if (one instanceof Map<?, ?> map) {
            Object status = ((Map<String, Object>) map).get("status");
            return status == null ? "FAILED" : String.valueOf(status);
        }
        return "FAILED";
    }
}

package com.loan.service;

import java.util.Map;

/**
 * 多源数据同步调度服务（W4 · 主线 B4/B5）
 *
 * 提供三种同步方式，并记录可观测的统计信息：
 *  - **批量**：对已授权用户全量拉取；
 *  - **增量**：只同步"数据缺失或已过期"的已授权用户；
 *  - **定时**：由 {@code @Scheduled} 触发（cron 与开关可通过配置控制）。
 */
public interface MultiSourceSyncService {

    /**
     * 批量同步。
     *
     * @param sourceType 数据源（为空则用全部已注册源）
     * @param limit      单次最多处理的用户数
     */
    Map<String, Object> syncBatch(String sourceType, Integer limit);

    /**
     * 增量同步。
     *
     * @param sourceType  数据源（为空则用全部已注册源）
     * @param staleHours  超过该小时数未同步视为"过期"
     * @param limit       单次最多处理的用户数
     */
    Map<String, Object> syncIncremental(String sourceType, Integer staleHours, Integer limit);

    /** 最近一次同步的统计（可观测性） */
    Map<String, Object> lastRunStats();
}

package com.loan.scorecard;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 评分引擎轻量指标（W5-A6）
 *
 * 线程安全的内存计数器，供 /api/v1/scorecard/v2/metrics 暴露：
 * 评分总次数、降级次数、平均耗时、各模型版本调用分布。
 * 说明：仅内存统计（进程级），重启归零；不引入额外依赖。
 */
@Component
public class ScoreMetrics {

    private final AtomicLong totalScores = new AtomicLong();

    private final AtomicLong degradedCount = new AtomicLong();

    private final AtomicLong totalElapsedMs = new AtomicLong();

    private final Map<String, AtomicLong> versionCounts = new ConcurrentHashMap<>();

    /**
     * 记录一次评分
     *
     * @param modelKey  实际使用的模型键
     * @param degraded  是否发生降级
     * @param elapsedMs 耗时（毫秒）
     */
    public void record(String modelKey, boolean degraded, long elapsedMs) {
        totalScores.incrementAndGet();
        if (degraded) {
            degradedCount.incrementAndGet();
        }
        if (elapsedMs > 0) {
            totalElapsedMs.addAndGet(elapsedMs);
        }
        if (modelKey != null) {
            versionCounts.computeIfAbsent(modelKey, k -> new AtomicLong()).incrementAndGet();
        }
    }

    /**
     * 指标快照
     *
     * 说明：`degradedRate` 是**降级监控**的核心指标 ——
     *     产品级阈值按 v4 分布标定，一旦降级到 v3，同一阈值的决策含义会变化（偏严），
     *     因此降级率持续偏高时需人工介入（详见《评分卡模型档位与降级机制.md》§3.6 / P-020）。
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        long total = totalScores.get();
        long degraded = degradedCount.get();
        result.put("totalScores", total);
        result.put("degradedCount", degraded);

        // 降级率（%，保留两位）
        double degradedRate = total == 0 ? 0.0
                : Math.round(degraded * 10000.0 / total) / 100.0;
        result.put("degradedRate", degradedRate);
        if (degradedRate >= 5.0) {
            result.put("degradedAlert", String.format(
                    "⚠️ 降级率 %.2f%% 偏高：当前决策口径可能偏离标定基准（阈值按 v4 标定，降级后判定偏严），"
                            + "请检查多源数据同步与模型加载状态", degradedRate));
        }

        result.put("avgElapsedMs", total == 0 ? 0 : totalElapsedMs.get() / total);

        Map<String, Object> versions = new LinkedHashMap<>();
        versionCounts.forEach((key, value) -> versions.put(key, value.get()));
        result.put("versionDistribution", versions);
        return result;
    }
}

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
     */
    public Map<String, Object> snapshot() {
        Map<String, Object> result = new LinkedHashMap<>();
        long total = totalScores.get();
        result.put("totalScores", total);
        result.put("degradedCount", degradedCount.get());
        result.put("avgElapsedMs", total == 0 ? 0 : totalElapsedMs.get() / total);

        Map<String, Object> versions = new LinkedHashMap<>();
        versionCounts.forEach((key, value) -> versions.put(key, value.get()));
        result.put("versionDistribution", versions);
        return result;
    }
}

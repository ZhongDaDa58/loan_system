package com.loan.spi;

import com.loan.service.MultiSourceSyncService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

import jakarta.annotation.Resource;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 多源统一调度测试（W4 · 主线 B2/B3/B4）
 *
 * 覆盖：数据源配置（开关/优先级/熔断状态）读取、开关切换、批量与增量同步的统计结构。
 */
@SpringBootTest
class MultiSourceSyncTest {

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    @Resource
    private MultiSourceSyncService multiSourceSyncService;

    @Test
    @DisplayName("注册表应暴露各数据源的开关/优先级/熔断状态，并提供健康检查")
    void registryShouldExposeConfigs() {
        List<Map<String, Object>> configs = dataSourceRegistry.listConfigs();
        assertFalse(configs.isEmpty(), "应至少有已注册的数据源");
        for (Map<String, Object> config : configs) {
            assertNotNull(config.get("sourceType"));
            assertNotNull(config.get("enabled"), "应包含开关状态");
            assertNotNull(config.get("priority"), "应包含优先级");
            assertNotNull(config.get("circuitStatus"), "应包含熔断状态");
        }
        List<Map<String, Object>> health = dataSourceRegistry.healthCheckAll();
        assertEquals(configs.size(), health.size(), "健康检查应覆盖全部数据源");
        assertNotNull(health.get(0).get("healthy"));

        System.out.println("✅ 数据源注册表：" + configs);
    }

    @Test
    @DisplayName("开关切换应生效并可恢复（读表驱动）")
    void toggleEnabledShouldWork() {
        String source = "device";
        boolean original = dataSourceRegistry.isEnabled(source);
        try {
            assertTrue(dataSourceRegistry.setEnabled(source, false));
            assertFalse(dataSourceRegistry.isEnabled(source), "停用后应不可用");
            assertFalse(dataSourceRegistry.isAvailable(source), "停用后 available 应为 false");
        } finally {
            dataSourceRegistry.setEnabled(source, original);
        }
        assertEquals(original, dataSourceRegistry.isEnabled(source), "测试后应恢复原开关状态");
    }

    @Test
    @DisplayName("批量同步应返回结构化统计（mode/users/perSource/elapsedMs）")
    void batchSyncShouldReturnStats() {
        Map<String, Object> stats = multiSourceSyncService.syncBatch("device", 5);

        assertNotNull(stats);
        assertEquals("BATCH", stats.get("mode"));
        assertNotNull(stats.get("users"));
        assertNotNull(stats.get("success"));
        assertNotNull(stats.get("failed"));
        assertNotNull(stats.get("perSource"));
        assertNotNull(stats.get("elapsedMs"));
        assertNotNull(stats.get("circuit"), "统计中应包含各源熔断状态");

        Map<String, Object> lastRun = multiSourceSyncService.lastRunStats();
        assertEquals(stats, lastRun, "lastRunStats 应返回最近一次运行结果");

        System.out.println("✅ 批量同步统计：" + stats);
    }

    @Test
    @DisplayName("增量同步应返回结构化统计（按 staleHours 过滤已过期用户）")
    void incrementalSyncShouldReturnStats() {
        Map<String, Object> stats = multiSourceSyncService.syncIncremental("device", 0, 5);

        assertNotNull(stats);
        assertEquals("INCREMENTAL", stats.get("mode"));
        assertNotNull(stats.get("users"));
        assertNotNull(stats.get("elapsedMs"));

        System.out.println("✅ 增量同步统计：" + stats);
    }
}

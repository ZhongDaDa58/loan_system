package com.loan.spi;

import com.loan.entity.DataSourceRegistryConfig;
import com.loan.mapper.DataSourceRegistryConfigMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 多数据源统一接入注册表（W4 · 主线 B2/B3）
 *
 * 职责：
 *  1) **注册**：启动扫描所有 {@link DataSourceProvider} Bean（W2 已有）；
 *  2) **开关与优先级（读表）**：从 `data_source_registry` 读取 enabled / priority，
 *     表内无记录时自动写入默认配置（enabled=1、priority=100、CLOSED），实现"配置化开关"；
 *  3) **熔断（B3）**：连续失败达到阈值即 OPEN（跳过取数），冷却后 HALF_OPEN 试探，
 *     成功则恢复 CLOSED；熔断状态写入 `circuit_status` 便于观测；
 *  4) **统一取数入口**：不可用/熔断时返回 null，由调用方兜底，不影响主流程。
 */
@Component
public class DataSourceRegistry {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRegistry.class);

    /** 连续失败阈值：达到即熔断 */
    private static final int FAILURE_THRESHOLD = 3;
    /** 熔断冷却时长（毫秒）：冷却后进入 HALF_OPEN 试探 */
    private static final long OPEN_COOLDOWN_MS = 60_000L;

    /** sourceType -> Provider */
    private final Map<String, DataSourceProvider> providerMap = new LinkedHashMap<>();

    /** sourceType -> 表配置（enabled/priority/circuitStatus）缓存 */
    private final Map<String, DataSourceRegistryConfig> configCache = new ConcurrentHashMap<>();

    /** sourceType -> 运行时失败计数（熔断用） */
    private final Map<String, Integer> failureCounters = new ConcurrentHashMap<>();

    /** sourceType -> 熔断开启时间 */
    private final Map<String, Long> openedAt = new ConcurrentHashMap<>();

    @Resource
    private List<DataSourceProvider> providers;

    @Resource
    private DataSourceRegistryConfigMapper registryConfigMapper;

    // ==================== 生命周期 ====================

    @PostConstruct
    public void init() {
        if (providers != null) {
            for (DataSourceProvider provider : providers) {
                if (provider == null || provider.sourceType() == null) {
                    continue;
                }
                providerMap.put(provider.sourceType(), provider);
            }
        }
        log.info("✅ 多数据源 Provider 注册完成，共 {} 个：{}", providerMap.size(), providerMap.keySet());
        refreshConfig();
    }

    /**
     * 从表加载开关/优先级配置；缺失的源自动写入默认配置（幂等）
     */
    public void refreshConfig() {
        try {
            List<DataSourceRegistryConfig> configs = registryConfigMapper.selectAll();
            Map<String, DataSourceRegistryConfig> byType = new LinkedHashMap<>();
            if (configs != null) {
                for (DataSourceRegistryConfig config : configs) {
                    byType.put(config.getSourceType(), config);
                }
            }

            for (String sourceType : providerMap.keySet()) {
                DataSourceRegistryConfig config = byType.get(sourceType);
                if (config == null) {
                    config = createDefaultConfig(sourceType);
                    try {
                        registryConfigMapper.insert(config);
                        log.info("🆕 数据源 {} 无注册记录，已写入默认配置（enabled=1, priority=100）", sourceType);
                    } catch (Exception e) {
                        log.warn("⚠️ 数据源 {} 默认配置写入失败（继续用内存默认值）: {}", sourceType, e.getMessage());
                    }
                }
                configCache.put(sourceType, config);
            }
            log.info("📦 数据源配置加载完成：{}", describeConfigs());
        } catch (Exception e) {
            // 数据库不可用时不阻塞启动：退化为内存默认（全部启用）
            log.warn("⚠️ 数据源配置读取失败，退化为内存默认（全部启用）: {}", e.getMessage());
        }
    }

    private DataSourceRegistryConfig createDefaultConfig(String sourceType) {
        DataSourceRegistryConfig config = new DataSourceRegistryConfig();
        config.setSourceType(sourceType);
        DataSourceProvider provider = providerMap.get(sourceType);
        config.setProviderName(provider == null ? "unknown" : provider.getClass().getSimpleName());
        config.setEnabled(1);
        config.setPriority(100);
        config.setCircuitStatus("CLOSED");
        config.setCreateTime(new Date());
        config.setUpdateTime(new Date());
        return config;
    }

    // ==================== 查询接口 ====================

    public DataSourceProvider getProvider(String sourceType) {
        return providerMap.get(sourceType);
    }

    public List<String> listSourceTypes() {
        return new ArrayList<>(providerMap.keySet());
    }

    public List<DataSourceProvider> listProviders() {
        return Collections.unmodifiableList(new ArrayList<>(providerMap.values()));
    }

    /** 是否可用：已注册 + 表开关开启 + 未熔断 */
    public boolean isAvailable(String sourceType) {
        return providerMap.containsKey(sourceType) && isEnabled(sourceType)
                && !isCircuitOpen(sourceType);
    }

    /**
     * 数据源开关：以**表配置**为准（缓存优先），无配置时退化用 Provider 自身开关
     */
    public boolean isEnabled(String sourceType) {
        DataSourceRegistryConfig config = configCache.get(sourceType);
        if (config != null && config.getEnabled() != null) {
            return config.getEnabled() == 1;
        }
        DataSourceProvider provider = providerMap.get(sourceType);
        return provider != null && provider.isEnabled();
    }

    /** 修改开关（写库 + 更新缓存） */
    public boolean setEnabled(String sourceType, boolean enabled) {
        if (!providerMap.containsKey(sourceType)) {
            return false;
        }
        try {
            registryConfigMapper.updateEnabled(sourceType, enabled ? 1 : 0);
        } catch (Exception e) {
            log.warn("⚠️ 开关写库失败（仍将更新内存）: {} - {}", sourceType, e.getMessage());
        }
        DataSourceRegistryConfig config = configCache.get(sourceType);
        if (config == null) {
            config = createDefaultConfig(sourceType);
            configCache.put(sourceType, config);
        }
        config.setEnabled(enabled ? 1 : 0);
        log.info("🔧 数据源 {} 开关已设为 {}", sourceType, enabled ? "启用" : "停用");
        return true;
    }

    /** 每个源的运行时状态（含熔断） */
    public List<Map<String, Object>> listConfigs() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (String sourceType : providerMap.keySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            DataSourceRegistryConfig config = configCache.get(sourceType);
            row.put("sourceType", sourceType);
            row.put("provider", providerMap.get(sourceType).getClass().getSimpleName());
            row.put("enabled", isEnabled(sourceType));
            row.put("priority", config == null ? 100 : config.getPriority());
            row.put("circuitStatus", circuitStatus(sourceType));
            row.put("failureCount", failureCounters.getOrDefault(sourceType, 0));
            row.put("available", isAvailable(sourceType));
            result.add(row);
        }
        return result;
    }

    // ==================== 熔断（B3） ====================

    public String circuitStatus(String sourceType) {
        String status = stateOf(sourceType);
        if ("OPEN".equals(status) && isCooldownElapsed(sourceType)) {
            return "HALF_OPEN";
        }
        return status;
    }

    private String stateOf(String sourceType) {
        DataSourceRegistryConfig config = configCache.get(sourceType);
        return config == null || config.getCircuitStatus() == null ? "CLOSED" : config.getCircuitStatus();
    }

    private boolean isCircuitOpen(String sourceType) {
        return "OPEN".equals(stateOf(sourceType)) && !isCooldownElapsed(sourceType);
    }

    private boolean isCooldownElapsed(String sourceType) {
        Long opened = openedAt.get(sourceType);
        return opened != null && System.currentTimeMillis() - opened >= OPEN_COOLDOWN_MS;
    }

    private void markSuccess(String sourceType) {
        failureCounters.put(sourceType, 0);
        openedAt.remove(sourceType);
        if (!"CLOSED".equals(stateOf(sourceType))) {
            updateCircuitStatus(sourceType, "CLOSED");
            log.info("🔌 数据源 {} 熔断恢复：CLOSED", sourceType);
        }
    }

    private void markFailure(String sourceType, String reason) {
        int failures = failureCounters.merge(sourceType, 1, Integer::sum);
        if (failures >= FAILURE_THRESHOLD && !"OPEN".equals(stateOf(sourceType))) {
            openedAt.put(sourceType, System.currentTimeMillis());
            updateCircuitStatus(sourceType, "OPEN");
            log.error("⚡ 数据源 {} 连续失败 {} 次，已熔断（OPEN），{} 秒后进入 HALF_OPEN 试探。原因: {}",
                    sourceType, failures, OPEN_COOLDOWN_MS / 1000, reason);
        }
    }

    private void updateCircuitStatus(String sourceType, String status) {
        DataSourceRegistryConfig config = configCache.get(sourceType);
        if (config != null) {
            config.setCircuitStatus(status);
        }
        try {
            registryConfigMapper.updateCircuitStatus(sourceType, status);
        } catch (Exception e) {
            log.debug("熔断状态写库失败（忽略）: {} - {}", sourceType, e.getMessage());
        }
    }

    /** 手工复位熔断（运维用） */
    public void resetCircuit(String sourceType) {
        failureCounters.put(sourceType, 0);
        openedAt.remove(sourceType);
        updateCircuitStatus(sourceType, "CLOSED");
        log.info("🔧 数据源 {} 熔断状态已手工复位", sourceType);
    }

    // ==================== 统一取数入口 ====================

    /**
     * 统一取数：不可用/熔断时返回 null（调用方兜底），失败累计触发熔断。
     */
    public MultiSourceData fetch(String sourceType, String userId) {
        DataSourceProvider provider = providerMap.get(sourceType);
        if (provider == null) {
            log.warn("⚠️ 数据源 {} 未注册，跳过取数", sourceType);
            return null;
        }
        if (!isEnabled(sourceType)) {
            log.info("ℹ️ 数据源 {} 已被配置停用，跳过取数", sourceType);
            return null;
        }
        if (isCircuitOpen(sourceType)) {
            log.warn("⚡ 数据源 {} 处于熔断中（OPEN），跳过取数", sourceType);
            return null;
        }
        if ("HALF_OPEN".equals(circuitStatus(sourceType))) {
            log.info("🔍 数据源 {} 进入 HALF_OPEN 试探取数", sourceType);
        }

        try {
            Map<String, Object> fields = provider.fetch(userId);
            markSuccess(sourceType);
            return new MultiSourceData(sourceType, fields, new Date());
        } catch (Exception e) {
            markFailure(sourceType, e.getMessage());
            log.error("❌ 数据源 {} 取数失败: {}", sourceType, e.getMessage(), e);
            return null;
        }
    }

    /** 全部源的健康检查（供运维/评审查看） */
    public List<Map<String, Object>> healthCheckAll() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (DataSourceProvider provider : providerMap.values()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("sourceType", provider.sourceType());
            row.put("circuitStatus", circuitStatus(provider.sourceType()));
            try {
                row.put("healthy", provider.healthCheck());
            } catch (Exception e) {
                row.put("healthy", false);
                row.put("error", e.getMessage());
            }
            result.add(row);
        }
        return result;
    }

    private List<String> describeConfigs() {
        List<String> rows = new ArrayList<>();
        for (String sourceType : providerMap.keySet()) {
            rows.add(sourceType + "=" + (isEnabled(sourceType) ? "ON" : "OFF")
                    + "/" + circuitStatus(sourceType));
        }
        return rows;
    }
}

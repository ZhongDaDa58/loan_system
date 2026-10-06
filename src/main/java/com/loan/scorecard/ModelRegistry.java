package com.loan.scorecard;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.scorecard.model.ScorecardModel;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 评分卡模型注册表（W4 · 主线 A）
 *
 * 启动时扫描 `classpath:models/model_config_*.json` 并逐个注册：
 *   - 单个模型加载失败**不影响**其它模型（状态标为 FAILED 并记录原因）；
 *   - 提供按版本取用与**自动降级**（请求的版本不可用时回退默认/可用模型）。
 *
 * 说明：本注册表与既有 {@code ScorecardServiceImpl}（现网单模型 v3）并存，
 * 既有评分端点与其行为**不受影响**。
 */
@Component
public class ModelRegistry {

    private static final Logger log = LoggerFactory.getLogger(ModelRegistry.class);

    /** 只加载评分卡配置，跳过 xgb_multi_source.json 等非配置资产 */
    private static final String CONFIG_PATTERN = "classpath:models/model_config_*.json";

    /** 兜底版本（自动降级时优先回退到它） */
    private static final String FALLBACK_KEY = "v3";

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Map<String, ScorecardModel> models = new LinkedHashMap<>();

    @Value("${scorecard.default-model-version:v4}")
    private String configuredDefaultKey;

    @PostConstruct
    public void loadAll() {
        log.info("🚀 开始扫描评分卡模型配置: {}", CONFIG_PATTERN);
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources(CONFIG_PATTERN);
            for (Resource resource : resources) {
                String fileName = resource.getFilename();
                String key = keyOf(fileName);
                try (InputStream in = resource.getInputStream()) {
                    Map<String, Object> config = objectMapper.readValue(in, new TypeReference<>() {
                    });
                    ScorecardModel model = buildModel(key, config, fileName);
                    models.put(key, model);
                    log.info("✅ 模型注册成功: key={}, version={}, type={}, features={}, AUC={}",
                            key, model.getVersion(), model.getModelType(),
                            model.getFeatures() == null ? 0 : model.getFeatures().size(),
                            model.metrics().get("auc"));
                } catch (Exception e) {
                    ScorecardModel failed = new ScorecardModel();
                    failed.setModelKey(key);
                    failed.setSourceFile(fileName);
                    failed.setStatus("FAILED");
                    failed.setErrorMessage(e.getMessage());
                    models.put(key, failed);
                    log.error("❌ 模型加载失败（不影响其它模型）: {} - {}", fileName, e.getMessage());
                }
            }
        } catch (Exception e) {
            log.error("❌ 评分卡模型扫描异常: {}", e.getMessage(), e);
        }

        if (models.isEmpty()) {
            log.error("❌ 未加载到任何评分卡模型，请检查 resources/models/ 目录");
        } else {
            log.info("📦 模型注册表就绪：可用 {}，默认版本 {}（请求版本不可用时自动降级到 {}）",
                    loadedKeys(), defaultKey(), FALLBACK_KEY);
        }
    }

    // ==================== 对外接口 ====================

    /** 全部模型（含加载失败的，便于排查） */
    public List<ScorecardModel> list() {
        return new ArrayList<>(models.values());
    }

    /** 按注册键取模型（不存在返回 null） */
    public ScorecardModel get(String key) {
        return models.get(key);
    }

    /** 默认版本键：配置项可用则用，否则回退 FALLBACK_KEY，再否则取任一可用模型 */
    public String defaultKey() {
        if (isAvailable(configuredDefaultKey)) {
            return configuredDefaultKey;
        }
        if (isAvailable(FALLBACK_KEY)) {
            return FALLBACK_KEY;
        }
        return models.values().stream()
                .filter(ScorecardModel::isLoaded)
                .map(ScorecardModel::getModelKey)
                .findFirst()
                .orElse(null);
    }

    /**
     * 解析请求的版本 → 实际可用模型（含自动降级）。
     *
     * @param requestedKey 请求的模型版本；为空则用默认版本
     * @return 实际使用的模型；全部不可用时返回 null
     */
    public ScorecardModel resolve(String requestedKey) {
        String target = (requestedKey == null || requestedKey.isBlank()) ? defaultKey() : requestedKey.trim();

        ScorecardModel model = models.get(target);
        if (model != null && model.isLoaded()) {
            return model;
        }

        if (model != null) {
            log.warn("⚠️ 请求的模型 {} 状态为 {}（{}），触发自动降级", target, model.getStatus(), model.getErrorMessage());
        } else {
            log.warn("⚠️ 请求的模型 {} 未注册，触发自动降级", target);
        }

        String fallback = defaultKey();
        if (fallback != null && !fallback.equals(target)) {
            ScorecardModel fallbackModel = models.get(fallback);
            if (fallbackModel != null && fallbackModel.isLoaded()) {
                log.warn("📉 已自动降级为模型: {}（version={}）", fallback, fallbackModel.getVersion());
                return fallbackModel;
            }
        }
        // 最后兜底：任一可用模型
        for (ScorecardModel candidate : models.values()) {
            if (candidate.isLoaded()) {
                log.warn("📉 默认模型不可用，兜底使用: {}", candidate.getModelKey());
                return candidate;
            }
        }
        return null;
    }

    public boolean isAvailable(String key) {
        ScorecardModel model = models.get(key);
        return model != null && model.isLoaded();
    }

    // ==================== 内部方法 ====================

    /** 文件名 -> 注册键：model_config_v4_fusion.json => v4-fusion */
    private String keyOf(String fileName) {
        if (fileName == null) {
            return "unknown";
        }
        String name = fileName.replace(".json", "");
        if (name.startsWith("model_config_")) {
            name = name.substring("model_config_".length());
        }
        return name.replace('_', '-');
    }

    @SuppressWarnings("unchecked")
    private ScorecardModel buildModel(String key, Map<String, Object> config, String fileName) {
        ScorecardModel model = new ScorecardModel();
        model.setModelKey(key);
        model.setSourceFile(fileName);
        model.setModelInfo((Map<String, Object>) config.get("model_info"));
        model.setScoreScale((Map<String, Object>) config.get("score_scale"));
        model.setCoefficients((Map<String, Object>) config.get("coefficients"));
        model.setFeatures((Map<String, Object>) config.get("features"));
        model.setThresholds((Map<String, Object>) config.get("thresholds"));

        if (model.getModelInfo() != null) {
            model.setVersion(String.valueOf(model.getModelInfo().getOrDefault("version", key)));
            model.setName(String.valueOf(model.getModelInfo().getOrDefault("name", key)));
            model.setModelType(String.valueOf(model.getModelInfo().getOrDefault("model_type", "lr")));
        } else {
            model.setVersion(key);
        }

        // 完整性校验：缺少必要结构即视为加载失败
        if (model.getScoreScale() == null || model.getCoefficients() == null
                || model.getFeatures() == null || model.getFeatures().isEmpty()) {
            throw new IllegalStateException("模型配置缺少必要字段（score_scale/coefficients/features）");
        }
        if (model.getThresholds() == null) {
            throw new IllegalStateException("模型配置缺少 thresholds");
        }
        model.setStatus("LOADED");
        return model;
    }

    private List<String> loadedKeys() {
        List<String> keys = new ArrayList<>();
        models.values().stream()
                .filter(ScorecardModel::isLoaded)
                .sorted(Comparator.comparing(ScorecardModel::getModelKey))
                .forEach(m -> keys.add(m.getModelKey()));
        return keys;
    }
}

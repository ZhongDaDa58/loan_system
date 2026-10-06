package com.loan.scorecard.model;

import lombok.Data;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 评分卡模型配置载体（W4 · 主线 A）
 *
 * 一个实例对应 `resources/models/` 下的一个配置文件；与 JSON 结构保持一致，
 * 便于 {@link com.loan.scorecard.ModelScorer} 通用化取分（不再硬编码特征名）。
 */
@Data
public class ScorecardModel {

    /** 注册键（由文件名推断：model_config_v3.json -> v3、model_config_v4_fusion.json -> v4-fusion） */
    private String modelKey;

    /** 配置内声明的版本号 */
    private String version;

    /** 模型类型（lr / lr+xgb） */
    private String modelType;

    /** 模型名称 */
    private String name;

    /** 状态：LOADED / FAILED */
    private String status = "LOADED";

    /** 加载失败原因（status=FAILED 时） */
    private String errorMessage;

    /** 来源文件 */
    private String sourceFile;

    /** 加载时间 */
    private Date loadedAt = new Date();

    private Map<String, Object> modelInfo;
    private Map<String, Object> scoreScale;
    private Map<String, Object> coefficients;
    private Map<String, Object> features;
    private Map<String, Object> thresholds;

    public boolean isLoaded() {
        return "LOADED".equals(status) && features != null && !features.isEmpty();
    }

    /** 该模型要求的全部特征名 */
    @SuppressWarnings("unchecked")
    public List<String> featureNames() {
        List<String> names = new ArrayList<>();
        if (features != null) {
            names.addAll(features.keySet());
        }
        return names;
    }

    // ========== 便捷取值（缺失时返回安全默认值） ==========

    private double number(Map<String, Object> map, String key, double fallback) {
        if (map == null || !(map.get(key) instanceof Number n)) {
            return fallback;
        }
        return n.doubleValue();
    }

    public double a() {
        return number(scoreScale, "A", 0);
    }

    public double b() {
        return number(scoreScale, "B", 0);
    }

    public double intercept() {
        if (coefficients == null || !(coefficients.get("intercept") instanceof Number n)) {
            return 0;
        }
        return n.doubleValue();
    }

    public int autoApprove() {
        return (int) number(thresholds, "auto_approve", 650);
    }

    public int manualReview() {
        return (int) number(thresholds, "manual_review", 600);
    }

    /** 指标（auc/ks 等），用于展示与评审 */
    @SuppressWarnings("unchecked")
    public Map<String, Object> metrics() {
        if (modelInfo != null && modelInfo.get("metrics") instanceof Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
        return Map.of();
    }

    @Override
    public String toString() {
        return "ScorecardModel{key=" + modelKey + ", version=" + version + ", type=" + modelType
                + ", status=" + status + ", features=" + (features == null ? 0 : features.size()) + "}";
    }
}

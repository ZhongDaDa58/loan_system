package com.loan.scorecard;

import com.loan.mapper.RiskDecisionLogStatMapper;
import com.loan.scorecard.model.ScorecardModel;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 模型质量评估服务（模型配置页的数据来源）
 *
 * 提供三类信息：
 *  1. **模型清单**：现有模型 + 默认模型标记 + AUC/KS/特征数（来自模型配置）
 *  2. **使用量**：每模型的评分调用量、决策分布（通过 / 转人工 / 拒绝）、平均分（来自 risk_decision_log）
 *  3. **质量评价**：
 *     - **IV**（Information Value，逐特征；来自模型配置，衡量特征区分力）
 *     - **PSI**（Population Stability Index，分数分布稳定性；近 30 天 vs 30 天前）
 *
 * PSI 口径：`Σ (实际占比 − 预期占比) × ln(实际占比 / 预期占比)`
 * 经验阈值：< 0.1 稳定；0.1 ~ 0.25 需关注；> 0.25 明显漂移（建议重训或复核）
 */
@Service
public class ModelQualityService {

    private static final Logger log = LoggerFactory.getLogger(ModelQualityService.class);

    /** 分数分桶粒度（用于 PSI 与分布展示） */
    private static final int BUCKET_SIZE = 10;

    @Resource
    private ModelRegistry modelRegistry;

    @Resource
    private RiskDecisionLogStatMapper statMapper;

    /**
     * 模型质量总览：模型清单 + 默认模型 + 使用量 + IV 汇总 + PSI
     */
    public Map<String, Object> overview() {
        Map<String, Object> result = new LinkedHashMap<>();
        String defaultKey = modelRegistry.defaultKey();
        result.put("defaultModel", defaultKey);
        result.put("bucketSize", BUCKET_SIZE);

        // 1) 使用量（按模型版本）
        Map<String, Map<String, Object>> usageByModel = new LinkedHashMap<>();
        try {
            for (Map<String, Object> row : statMapper.countDecisionsByModelVersion()) {
                usageByModel.put(String.valueOf(row.get("modelVersion")), row);
            }
        } catch (Exception e) {
            log.warn("⚠️ 模型调用量统计失败: {}", e.getMessage());
        }

        // 2) PSI（按模型版本，近 30 天 vs 30 天前）
        Map<String, Double> psiByModel = new LinkedHashMap<>();
        try {
            Map<String, List<Map<String, Object>>> buckets = new LinkedHashMap<>();
            for (Map<String, Object> row : statMapper.countScoreBucketsByModelVersion(BUCKET_SIZE)) {
                buckets.computeIfAbsent(String.valueOf(row.get("modelVersion")), k -> new ArrayList<>()).add(row);
            }
            buckets.forEach((key, rows) -> psiByModel.put(key, computePsi(rows)));
        } catch (Exception e) {
            log.warn("⚠️ PSI 统计失败: {}", e.getMessage());
        }

        // 3) 模型清单 + 质量指标
        List<Map<String, Object>> models = new ArrayList<>();
        for (ScorecardModel model : modelRegistry.list()) {
            models.add(buildModelView(model, defaultKey, usageByModel, psiByModel));
        }
        models.sort(Comparator.comparing(m -> String.valueOf(m.get("modelKey"))));
        result.put("models", models);
        result.put("modelCount", models.size());
        return result;
    }

    /**
     * 单模型的特征级质量（IV 明细）
     */
    public Map<String, Object> featureQuality(String modelKey) {
        ScorecardModel model = modelRegistry.get(modelKey);
        if (model == null) {
            return null;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelKey", model.getModelKey());
        result.put("version", model.getVersion());
        result.put("featureCount", model.featureNames().size());

        List<Map<String, Object>> features = new ArrayList<>();
        double ivSum = 0.0;
        int strong = 0;
        for (String name : model.featureNames()) {
            Object cfgObj = model.getFeatures() == null ? null : model.getFeatures().get(name);
            Double iv = null;   // null = 该模型配置未记录 IV（如早期 v3），区别于"IV=0 无区分力"
            int binCount = 0;
            String source = "—";
            if (cfgObj instanceof Map<?, ?> cfg) {
                Object ivObj = cfg.get("iv");
                if (ivObj instanceof Number n) {
                    iv = n.doubleValue();
                }
                Object bins = cfg.get("bins");
                if (bins instanceof List<?> list) {
                    binCount = Math.max(list.size() - 1, 0);
                }
                Object srcObj = cfg.get("source");
                if (srcObj != null) {
                    source = String.valueOf(srcObj);
                }
            }
            if (iv != null) {
                ivSum += iv;
                if (iv >= 0.3) {
                    strong++;
                }
            }
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("feature", name);
            f.put("iv", iv == null ? null : Math.round(iv * 10000.0) / 10000.0);
            f.put("ivLevel", iv == null ? "未记录" : ivLevel(iv));
            f.put("binCount", binCount);
            f.put("source", source);
            f.put("coefficient", coefficientOf(model, name));
            features.add(f);
        }
        // IV 未记录的模型（如 v3 / model_config.json）此处 iv 为 null —— 必须容忍，
        // 否则排序时对 null 解引用会抛 NPE（该路径经 /scorecard/v2/quality/{key}/features 对外暴露）
        features.sort(Comparator.comparingDouble(
                (Map<String, Object> m) -> m.get("iv") instanceof Number n ? -n.doubleValue() : 0d));

        boolean anyIv = features.stream().anyMatch(m -> m.get("iv") != null);
        result.put("features", features);
        result.put("ivSum", anyIv ? Math.round(ivSum * 10000.0) / 10000.0 : null);
        result.put("ivAvg", anyIv && !features.isEmpty()
                ? Math.round(ivSum / features.size() * 10000.0) / 10000.0 : null);
        result.put("strongFeatureCount", strong);
        result.put("hasIv", anyIv);
        result.put("note", "IV 经验阈值：<0.02 无区分力；0.02~0.1 弱；0.1~0.3 中等；>0.3 强");
        return result;
    }

    // ==================== 内部方法 ====================

    private Map<String, Object> buildModelView(ScorecardModel model, String defaultKey,
                                               Map<String, Map<String, Object>> usageByModel,
                                               Map<String, Double> psiByModel) {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("modelKey", model.getModelKey());
        v.put("name", model.getName());
        v.put("version", model.getVersion());
        v.put("modelType", model.getModelType() == null || model.getModelType().isBlank()
                ? "lr" : model.getModelType());
        v.put("featureCount", model.featureNames().size());
        v.put("loaded", model.isLoaded());
        v.put("defaultModel", model.getModelKey().equals(defaultKey));
        v.put("status", model.getStatus());
        v.put("autoApprove", model.autoApprove());
        v.put("manualReview", model.manualReview());

        // 训练侧指标（model_info.metrics）
        Map<String, Object> info = model.getModelInfo() == null ? Map.of() : model.getModelInfo();
        Object metricsObj = info.get("metrics");
        if (metricsObj instanceof Map<?, ?> metrics) {
            v.put("auc", metrics.get("auc"));
            v.put("ks", metrics.get("ks"));
        }
        v.put("trainDate", info.get("train_date"));
        v.put("dataSource", info.get("data_source"));

        // IV 汇总（未记录 IV 的模型返回 null，页面显示"—"，避免误显示为 0）
        double ivSum = 0.0;
        int strong = 0;
        boolean anyIv = false;
        for (String name : model.featureNames()) {
            Object cfgObj = model.getFeatures() == null ? null : model.getFeatures().get(name);
            if (cfgObj instanceof Map<?, ?> cfg && cfg.get("iv") instanceof Number n) {
                anyIv = true;
                ivSum += n.doubleValue();
                if (n.doubleValue() >= 0.3) {
                    strong++;
                }
            }
        }
        v.put("ivSum", anyIv ? Math.round(ivSum * 10000.0) / 10000.0 : null);
        v.put("strongFeatureCount", anyIv ? strong : null);
        v.put("hasIv", anyIv);

        // 使用量：严格按模型版本匹配（未记录 model_version 的历史数据归入 unknown，不摊派给具体模型）
        Map<String, Object> usage = usageByModel.get(model.getModelKey());
        long total = usage == null ? 0 : toLong(usage.get("totalCount"));
        long auto = usage == null ? 0 : toLong(usage.get("autoApproveCount"));
        long manual = usage == null ? 0 : toLong(usage.get("manualReviewCount"));
        long reject = usage == null ? 0 : toLong(usage.get("rejectCount"));
        // P-025：降额单列统计（降额 ≠ 拒绝）—— 统计 SQL 已产出该列，此前未透传到接口
        long downgrade = usage == null ? 0 : toLong(usage.get("downgradeCount"));
        v.put("scoreCount", total);
        v.put("autoApproveCount", auto);
        v.put("manualReviewCount", manual);
        v.put("downgradeCount", downgrade);
        v.put("rejectCount", reject);
        v.put("avgScore", usage == null ? null : usage.get("avgScore"));
        v.put("passRate", total == 0 ? null : Math.round(auto * 10000.0 / total) / 100.0);
        v.put("manualRate", total == 0 ? null : Math.round(manual * 10000.0 / total) / 100.0);
        v.put("rejectRate", total == 0 ? null : Math.round(reject * 10000.0 / total) / 100.0);

        // PSI（分数分布稳定性）：需两期都有样本，否则视为"样本不足"
        Double psi = psiByModel.get(model.getModelKey());
        if (psi == null || psi <= 0) {
            v.put("psi", null);
            v.put("psiLevel", "样本不足");
        } else {
            v.put("psi", Math.round(psi * 10000.0) / 10000.0);
            v.put("psiLevel", psiLevel(psi));
        }
        return v;
    }

    /**
     * PSI：近 30 天分布 vs 30 天前分布
     * 公式：Σ (A% − B%) × ln(A% / B%)
     */
    private double computePsi(List<Map<String, Object>> rows) {
        double recentTotal = 0, baseTotal = 0;
        for (Map<String, Object> r : rows) {
            recentTotal += toLong(r.get("recentCount"));
            baseTotal += toLong(r.get("baseCount"));
        }
        if (recentTotal <= 0 || baseTotal <= 0) {
            return 0.0;   // 某期为空 → 无法比较
        }
        double psi = 0.0;
        for (Map<String, Object> r : rows) {
            double a = toLong(r.get("recentCount")) / recentTotal;
            double b = toLong(r.get("baseCount")) / baseTotal;
            if (a > 0 && b > 0) {
                psi += (a - b) * Math.log(a / b);
            }
        }
        return psi;
    }

    private String ivLevel(double iv) {
        if (iv < 0.02) return "无区分力";
        if (iv < 0.1) return "弱";
        if (iv < 0.3) return "中等";
        return "强";
    }

    private String psiLevel(double psi) {
        if (psi < 0.1) return "稳定";
        if (psi <= 0.25) return "需关注";
        return "明显漂移";
    }

    private Object coefficientOf(ScorecardModel model, String feature) {
        if (model.getCoefficients() == null) {
            return null;
        }
        Object feats = model.getCoefficients().get("features");
        if (feats instanceof Map<?, ?> map) {
            Object c = map.get(feature);
            if (c instanceof Number n) {
                return Math.round(n.doubleValue() * 10000.0) / 10000.0;
            }
        }
        return null;
    }

    private long toLong(Object v) {
        if (v instanceof Number n) {
            return n.longValue();
        }
        try {
            return v == null ? 0 : Long.parseLong(String.valueOf(v));
        } catch (Exception e) {
            return 0;
        }
    }
}

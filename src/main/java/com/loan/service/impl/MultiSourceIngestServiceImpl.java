package com.loan.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.loan.entity.UserMultiSourceData;
import com.loan.entity.enums.SourceTypeEnum;
import com.loan.mapper.UserMultiSourceDataMapper;
import com.loan.service.MultiSourceIngestService;
import com.loan.spi.DataSourceRegistry;
import com.loan.spi.MultiSourceData;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源数据接入服务实现（W3 · B 线）
 *
 * 流程：SPI 拉取（Provider）→ 标准化清洗（白名单/区间/缺失）→ upsert 落库。
 * 清洗规则集中在 {@link #clean}，确保进入宽表的字段口径统一、可被评分卡直接消费。
 */
@Service
public class MultiSourceIngestServiceImpl implements MultiSourceIngestService {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceIngestServiceImpl.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String DATA_VERSION = "v1";

    /** 各数据源的字段白名单（与《多源数据.md》一致，清洗时过滤非法字段） */
    private static final Map<String, List<String>> ALLOWED_FIELDS = new HashMap<>();
    /** 数值区间规则：字段 -> [最小值, 最大值]（越界截断，防止脏数据进入引擎） */
    private static final Map<String, double[]> RANGE_RULES = new HashMap<>();

    static {
        ALLOWED_FIELDS.put(SourceTypeEnum.CREDIT.getCode(), List.of(
                "credit_query_cnt_3m", "credit_query_cnt_6m", "debt_to_income",
                "hist_overdue_cnt_m1", "hist_overdue_cnt_m2", "hist_overdue_cnt_m3"));
        ALLOWED_FIELDS.put(SourceTypeEnum.OPERATOR.getCode(), List.of(
                "operator_realname_months", "operator_tenure_months", "operator_pay_stability"));
        ALLOWED_FIELDS.put(SourceTypeEnum.SOCIAL.getCode(), List.of(
                "social_contrib_months", "social_base", "social_employer_cnt"));
        ALLOWED_FIELDS.put(SourceTypeEnum.DEVICE.getCode(), List.of(
                "device_fingerprint_hash", "device_emulator_flag", "device_blacklist_hit_now",
                "device_multi_account_now", "device_blacklist_hit_cnt",
                "device_related_app_cnt_30d", "device_first_seen_months",
                "ip_proxy_risk", "ip_geo_mismatch"));

        RANGE_RULES.put("credit_query_cnt_3m", new double[]{0, 24});
        RANGE_RULES.put("credit_query_cnt_6m", new double[]{0, 48});
        RANGE_RULES.put("debt_to_income", new double[]{0, 1});
        RANGE_RULES.put("hist_overdue_cnt_m1", new double[]{0, 24});
        RANGE_RULES.put("hist_overdue_cnt_m2", new double[]{0, 24});
        RANGE_RULES.put("hist_overdue_cnt_m3", new double[]{0, 24});
        RANGE_RULES.put("operator_realname_months", new double[]{0, 240});
        RANGE_RULES.put("operator_tenure_months", new double[]{0, 240});
        RANGE_RULES.put("operator_pay_stability", new double[]{0, 36});
        RANGE_RULES.put("social_contrib_months", new double[]{0, 480});
        RANGE_RULES.put("social_base", new double[]{0, 100000});
        RANGE_RULES.put("social_employer_cnt", new double[]{0, 20});
        RANGE_RULES.put("device_blacklist_hit_cnt", new double[]{0, 20});
        RANGE_RULES.put("device_related_app_cnt_30d", new double[]{0, 100});
        RANGE_RULES.put("device_first_seen_months", new double[]{0, 240});
        // W4 主线 B1：设备指纹/反欺诈新增字段的取值区间
        RANGE_RULES.put("device_emulator_flag", new double[]{0, 1});
        RANGE_RULES.put("device_blacklist_hit_now", new double[]{0, 1});
        RANGE_RULES.put("device_multi_account_now", new double[]{0, 1});
        RANGE_RULES.put("ip_proxy_risk", new double[]{0, 1});
        RANGE_RULES.put("ip_geo_mismatch", new double[]{0, 1});

        // App 端行为埋点（客户端直接上报）：白名单里只放"派生后的特征名"
        ALLOWED_FIELDS.put(SourceTypeEnum.BEHAVIOR.getCode(), List.of(
                "behavior_anomaly_score"));
        RANGE_RULES.put("behavior_anomaly_score", new double[]{0, 1});
    }

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    @Resource
    private UserMultiSourceDataMapper multiSourceDataMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> sync(String userId, String sourceType) {
        List<String> targets = new ArrayList<>();
        if (sourceType != null && !sourceType.isBlank()) {
            targets.add(sourceType.trim().toLowerCase());
        } else {
            targets.addAll(dataSourceRegistry.listSourceTypes());
        }

        Map<String, Object> result = new LinkedHashMap<>();
        for (String source : targets) {
            Map<String, Object> one = new LinkedHashMap<>();
            try {
                MultiSourceData fetched = dataSourceRegistry.fetch(source, userId);
                if (fetched == null || fetched.getFields() == null || fetched.getFields().isEmpty()) {
                    one.put("status", "EMPTY");
                    one.put("message", "数据源未返回数据（可能未启用或 Provider 无数据）");
                    result.put(source, one);
                    continue;
                }

                Map<String, Object> cleaned = clean(source, fetched.getFields());
                if (cleaned.isEmpty()) {
                    one.put("status", "FAILED");
                    one.put("message", "清洗后无有效字段");
                    result.put(source, one);
                    continue;
                }

                upsert(userId, source, cleaned);
                one.put("status", "SUCCESS");
                one.put("fieldCount", cleaned.size());
                one.put("fields", cleaned);
                one.put("fetchedAt", new Date());
                log.info("✅ 多源同步成功：用户 {} 源 {} 入库 {} 个字段", userId, source, cleaned.size());
            } catch (Exception e) {
                one.put("status", "FAILED");
                one.put("message", e.getMessage());
                log.error("❌ 多源同步失败：用户 {} 源 {} - {}", userId, source, e.getMessage(), e);
            }
            result.put(source, one);
        }
        result.put("userId", userId);
        return result;
    }

    @Override
    public Map<String, Object> featureSnapshot(String userId) {
        Map<String, Object> features = new LinkedHashMap<>();
        Map<String, Object> meta = new LinkedHashMap<>();

        List<UserMultiSourceData> records = multiSourceDataMapper.selectByUserId(userId);
        if (records == null || records.isEmpty()) {
            features.put("userId", userId);
            features.put("features", new LinkedHashMap<>());
            features.put("sources", new LinkedHashMap<>());
            features.put("message", "尚未同步任何多源数据");
            return features;
        }

        for (UserMultiSourceData record : records) {
            try {
                Map<String, Object> payload = OBJECT_MAPPER.readValue(record.getDataJson(), Map.class);
                Map<String, Object> sourceMeta = new LinkedHashMap<>();
                sourceMeta.put("fetchStatus", record.getFetchStatus());
                sourceMeta.put("fetchedAt", record.getFetchedAt());
                sourceMeta.put("dataVersion", record.getDataVersion());
                meta.put(record.getSourceType(), sourceMeta);

                for (Map.Entry<String, Object> entry : payload.entrySet()) {
                    if ("data_version".equals(entry.getKey())) {
                        continue; // 版本号属于元数据，不作为特征
                    }
                    features.put(entry.getKey(), entry.getValue());
                }
            } catch (Exception e) {
                log.warn("⚠️ 解析多源数据失败：用户 {} 源 {} - {}", userId, record.getSourceType(), e.getMessage());
            }
        }

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("userId", userId);
        snapshot.put("featureCount", features.size());
        snapshot.put("features", features);
        snapshot.put("sources", meta);
        return snapshot;
    }

    // ========== 内部方法 ==========

    /**
     * 标准化清洗：白名单过滤 + 数值区间截断 + 丢弃空值
     */
    /**
     * 按数据源直接写入特征字段（清洗 → upsert）
     * 用途：模拟金融信息等"非 SPI"来源写入多源特征（credit/operator/social）
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ingestFields(String userId, String sourceType, Map<String, Object> fields) {
        Map<String, Object> cleaned = clean(sourceType, fields == null ? new LinkedHashMap<>() : fields);
        upsert(userId, sourceType, cleaned);
        cleaned.remove("data_version");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceType", sourceType);
        result.put("fieldCount", cleaned.size());
        result.put("fields", cleaned);
        log.info("📥 [多源特征写入] 用户: {}, 源: {}, 字段: {}", userId, sourceType, cleaned);
        return result;
    }

    /**
     * App 端事件上报落库（多源"入口"打通）
     *
     * 处理规则：
     *  - eventType = device   → source_type=device，保留白名单字段并派生 device_first_seen_months；
     *  - eventType = behavior → source_type=behavior，派生 behavior_anomaly_score；
     *  - 其他（如 location）  → 暂不作为特征，计入 skipped（位置类特征待后续扩展）。
     * 落库前统一走 {@link #clean} 白名单过滤与区间截断，避免原始字段污染特征空间。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> ingestEvents(String userId, java.util.List<com.loan.entity.dto.MultiSourceEventDTO> events) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Map<String, Object>> bySource = new LinkedHashMap<>();
        int skipped = 0;

        if (events != null) {
            for (com.loan.entity.dto.MultiSourceEventDTO event : events) {
                try {
                    String type = event.getEventType() == null ? "" : event.getEventType().trim().toLowerCase();
                    Map<String, Object> data = event.getEventData() == null
                            ? new LinkedHashMap<>() : event.getEventData();

                    if ("device".equals(type)) {
                        Map<String, Object> raw = bySource.computeIfAbsent(
                                SourceTypeEnum.DEVICE.getCode(), k -> new LinkedHashMap<>());
                        raw.putAll(data);
                        raw.put("device_first_seen_months", monthsSince(data.get("first_seen_at")));
                    } else if ("behavior".equals(type)) {
                        Map<String, Object> raw = bySource.computeIfAbsent(
                                SourceTypeEnum.BEHAVIOR.getCode(), k -> new LinkedHashMap<>());
                        raw.putAll(data);
                        raw.put("behavior_anomaly_score", behaviorAnomalyScore(data));
                    } else {
                        skipped++;
                    }
                } catch (Exception e) {
                    skipped++;
                    log.warn("⚠️ 事件处理失败（已跳过）: {}", e.getMessage());
                }
            }
        }

        Map<String, Object> saved = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Object>> entry : bySource.entrySet()) {
            Map<String, Object> cleaned = clean(entry.getKey(), entry.getValue());
            upsert(userId, entry.getKey(), cleaned);
            cleaned.remove("data_version");
            saved.put(entry.getKey(), cleaned);
        }

        result.put("accepted", events == null ? 0 : events.size());
        result.put("skipped", skipped);
        result.put("saved", saved);
        log.info("📥 [多源事件落库] 用户: {}, 接收: {}, 落库源: {}, 跳过: {}, 特征: {}",
                userId, events == null ? 0 : events.size(), saved.keySet(), skipped, saved);
        return result;
    }

    /** 首次出现时间（时间戳/纯数字字符串）→ 距今月数（保留 2 位） */
    private double monthsSince(Object firstSeenAt) {
        try {
            long ts;
            if (firstSeenAt instanceof Number n) {
                ts = n.longValue();
            } else if (firstSeenAt != null) {
                String s = String.valueOf(firstSeenAt).trim();
                if (!s.matches("\\d+")) {
                    return 0;
                }
                ts = Long.parseLong(s);
            } else {
                return 0;
            }
            long diffMs = System.currentTimeMillis() - ts;
            if (diffMs <= 0) {
                return 0;
            }
            return Math.round(diffMs / (30.0 * 24 * 3600 * 1000) * 100.0) / 100.0;
        } catch (Exception e) {
            return 0;
        }
    }

    /**
     * 行为异常分（0~1，规则化计算）
     * 依据客户端埋点：字段顺序异常、填表过快、字段数缺失、填写耗时过长
     */
    private double behaviorAnomalyScore(Map<String, Object> data) {
        double score = 0.0;
        double orderAnomaly = toDouble(data.get("field_order_anomaly"), 0);
        double formFillMs = toDouble(data.get("form_fill_ms"), -1);
        double fieldCount = toDouble(data.get("field_count"), -1);
        double durationMs = toDouble(data.get("duration_ms"), -1);

        if (orderAnomaly > 0) score += 0.4;                       // 字段顺序被改动，疑似脚本填充
        if (formFillMs >= 0 && formFillMs < 2000) score += 0.3;   // 填表过快
        if (fieldCount == 0) score += 0.2;                        // 字段数异常（埋点缺失）
        if (durationMs > 600000) score += 0.1;                    // 停留过久

        return Math.min(1.0, Math.round(score * 100.0) / 100.0);
    }

    private double toDouble(Object v, double defaultValue) {
        if (v instanceof Number n) {
            return n.doubleValue();
        }
        if (v != null) {
            try {
                return Double.parseDouble(String.valueOf(v));
            } catch (Exception ignored) {
                // 非数值按默认值处理
            }
        }
        return defaultValue;
    }

    private Map<String, Object> clean(String sourceType, Map<String, Object> raw) {
        Map<String, Object> cleaned = new LinkedHashMap<>();
        List<String> allowed = ALLOWED_FIELDS.getOrDefault(sourceType, List.of());

        for (String field : allowed) {
            Object value = raw.get(field);
            if (value == null) {
                continue; // 缺失不做填充，交由下游兜底策略处理
            }
            if (value instanceof Number) {
                double v = ((Number) value).doubleValue();
                double[] range = RANGE_RULES.get(field);
                if (range != null) {
                    v = Math.max(range[0], Math.min(range[1], v));
                }
                cleaned.put(field, v);
            } else {
                cleaned.put(field, value);
            }
        }
        // 版本号透传（元数据）
        Object version = raw.get("data_version");
        cleaned.put("data_version", version == null ? DATA_VERSION : String.valueOf(version));
        return cleaned;
    }

    /**
     * 落库（存在则更新，否则新增）
     */
    private void upsert(String userId, String sourceType, Map<String, Object> cleaned) {
        String json;
        try {
            json = OBJECT_MAPPER.writeValueAsString(cleaned);
        } catch (Exception e) {
            throw new IllegalStateException("多源数据序列化失败: " + e.getMessage(), e);
        }

        Date now = new Date();
        UserMultiSourceData existing =
                multiSourceDataMapper.selectLatestByUserIdAndSourceType(userId, sourceType);

        if (existing == null) {
            UserMultiSourceData record = new UserMultiSourceData();
            record.setUserId(userId);
            record.setSourceType(sourceType);
            record.setDataJson(json);
            record.setDataVersion(DATA_VERSION);
            record.setFetchStatus("SUCCESS");
            record.setFetchedAt(now);
            record.setCreateTime(now);
            record.setUpdateTime(now);
            multiSourceDataMapper.insert(record);
        } else {
            existing.setDataJson(json);
            existing.setDataVersion(DATA_VERSION);
            existing.setFetchStatus("SUCCESS");
            existing.setFetchedAt(now);
            multiSourceDataMapper.updateData(existing);
        }
    }
}

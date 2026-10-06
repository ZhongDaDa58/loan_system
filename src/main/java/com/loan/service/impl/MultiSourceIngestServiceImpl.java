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

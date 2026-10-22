package com.loan.service.impl;

import com.loan.entity.dto.MultiSourceEventDTO;
import com.loan.entity.enums.SourceTypeEnum;
import com.loan.entity.vo.DataSourceAuthorizationVO;
import com.loan.entity.vo.MultiSourceProfileVO;
import com.loan.entity.vo.Result;
import com.loan.service.DataSourceService;
import com.loan.service.MultiSourceIngestService;
import com.loan.service.MultiSourceService;
import com.loan.spi.DataSourceRegistry;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 多源数据服务实现
 *
 * W2：摘要接口返回授权状态 + 空数据块（结构先行）。
 * W5-B1/B2：数据块升级为**真实脱敏摘要**——按来源分组、敏感标识打码。
 */
@Service
public class MultiSourceServiceImpl implements MultiSourceService {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceServiceImpl.class);

    /**
     * 数据口径版本（对齐《多源数据.md》）
     */
    private static final String DATA_VERSION = "v1";

    /** 需要打码的字段：仅保留前 N 位 */
    private static final int HASH_KEEP_PREFIX = 8;

    @Resource
    private DataSourceService dataSourceService;

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    /** W5-B1：多源特征宽表（来源分组的数据基础） */
    @Resource
    private MultiSourceIngestService multiSourceIngestService;

    @Override
    public Result<MultiSourceProfileVO> getProfile(String userId) {
        MultiSourceProfileVO vo = new MultiSourceProfileVO();
        vo.setUserId(userId);

        // 1. 各源授权状态（含 PENDING 占位）
        List<DataSourceAuthorizationVO> authorizations =
                dataSourceService.queryAuthorizationVOs(userId);
        vo.setAuthorizations(authorizations);

        // 2. 各源脱敏数据块（W5：真实摘要，按来源分组）
        vo.setDataBlocks(buildDataBlocks(userId));

        // 3. 已注册的数据源（来自注册表）
        vo.setRegisteredSources(dataSourceRegistry.listSourceTypes());
        vo.setDataVersion(DATA_VERSION);

        return Result.success(vo);
    }

    @Override
    public Result<?> reportEvents(String userId, List<MultiSourceEventDTO> events) {
        if (events == null || events.isEmpty()) {
            return Result.error(400, "上报事件不能为空");
        }

        // 落库为多源特征（device / behavior）：经白名单过滤与区间截断后写入 user_multi_source_data，
        // 这样 App 采集的数据才能真正进入特征宽表，参与评分卡与规则判定
        Map<String, Object> ingestResult = multiSourceIngestService.ingestEvents(userId, events);
        log.info("📥 [多源事件上报] 用户: {}, 接收: {}, 落库: {}",
                userId, events.size(), ingestResult.get("saved"));
        return Result.success(ingestResult);
    }

    // ==================== W5-B1/B2：脱敏数据块构建 ====================

    /**
     * 构建按来源分组的脱敏摘要：
     * credit / operator / social / device / behavior（GPS·IP·行为）/ other
     */
    private Map<String, Object> buildDataBlocks(String userId) {
        Map<String, Object> blocks = new LinkedHashMap<>();
        // 正式四源 + 行为类（GPS/IP/行为埋点）
        blocks.put(SourceTypeEnum.CREDIT.getCode(), new LinkedHashMap<String, Object>());
        blocks.put(SourceTypeEnum.OPERATOR.getCode(), new LinkedHashMap<String, Object>());
        blocks.put(SourceTypeEnum.SOCIAL.getCode(), new LinkedHashMap<String, Object>());
        blocks.put(SourceTypeEnum.DEVICE.getCode(), new LinkedHashMap<String, Object>());
        blocks.put("behavior", new LinkedHashMap<String, Object>());
        // 阶段 C3：位置数据块（供第一层交叉验证读取 latitude/longitude）
        blocks.put(SourceTypeEnum.LOCATION.getCode(), new LinkedHashMap<String, Object>());

        try {
            Map<String, Object> snapshot = multiSourceIngestService.featureSnapshot(userId);
            Object featuresObj = snapshot == null ? null : snapshot.get("features");
            if (featuresObj instanceof Map<?, ?> features) {
                for (Map.Entry<?, ?> entry : features.entrySet()) {
                    if (entry.getKey() == null) {
                        continue;
                    }
                    String field = String.valueOf(entry.getKey());
                    if ("userId".equals(field)) {
                        continue; // 元数据不作为特征展示
                    }
                    String group = groupOf(field);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> target = (Map<String, Object>) blocks
                            .computeIfAbsent(group, k -> new LinkedHashMap<String, Object>());
                    target.put(field, mask(field, entry.getValue()));
                }
            }
        } catch (Exception e) {
            log.warn("⚠️ 多源摘要构建失败（返回空块）: {}", e.getMessage());
        }
        return blocks;
    }

    /**
     * 字段归属分组（按字段名前缀映射到数据源）
     */
    private String groupOf(String field) {
        if (field.startsWith("credit_") || field.startsWith("hist_overdue")
                || field.startsWith("debt_to_income")) {
            return SourceTypeEnum.CREDIT.getCode();
        }
        if (field.startsWith("operator_")) {
            return SourceTypeEnum.OPERATOR.getCode();
        }
        if (field.startsWith("social_")) {
            return SourceTypeEnum.SOCIAL.getCode();
        }
        if (field.startsWith("device_")) {
            return SourceTypeEnum.DEVICE.getCode();
        }
        if (field.startsWith("gps_") || field.startsWith("behavior_") || field.startsWith("ip_")) {
            return "behavior";
        }
        // 阶段 C3：经纬度归入 location 块（第一层交叉验证用；此前会落到 "other" 而读不到）
        if ("latitude".equals(field) || "longitude".equals(field)) {
            return SourceTypeEnum.LOCATION.getCode();
        }
        return "other";
    }

    /**
     * 脱敏：设备指纹等标识只保留前缀，其余字段原值展示（数值特征无个人敏感信息）
     */
    private Object mask(String field, Object value) {
        if (value == null) {
            return null;
        }
        if ("device_fingerprint_hash".equals(field) && value instanceof String hash) {
            return hash.length() > HASH_KEEP_PREFIX
                    ? hash.substring(0, HASH_KEEP_PREFIX) + "********"
                    : hash;
        }
        return value;
    }
}

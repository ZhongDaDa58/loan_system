package com.loan.service.impl;

import com.loan.entity.dto.MultiSourceEventDTO;
import com.loan.entity.enums.SourceTypeEnum;
import com.loan.entity.vo.DataSourceAuthorizationVO;
import com.loan.entity.vo.MultiSourceProfileVO;
import com.loan.entity.vo.Result;
import com.loan.service.DataSourceService;
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
 * 本期（W2）：摘要接口返回授权状态 + 空数据块（结构先行）；事件上报仅记录日志不落库。
 */
@Service
public class MultiSourceServiceImpl implements MultiSourceService {

    private static final Logger log = LoggerFactory.getLogger(MultiSourceServiceImpl.class);

    /**
     * 数据口径版本（对齐《多源数据.md》）
     */
    private static final String DATA_VERSION = "v1";

    @Resource
    private DataSourceService dataSourceService;

    @Resource
    private DataSourceRegistry dataSourceRegistry;

    @Override
    public Result<MultiSourceProfileVO> getProfile(String userId) {
        MultiSourceProfileVO vo = new MultiSourceProfileVO();
        vo.setUserId(userId);

        // 1. 各源授权状态（含 PENDING 占位）
        List<DataSourceAuthorizationVO> authorizations =
                dataSourceService.queryAuthorizationVOs(userId);
        vo.setAuthorizations(authorizations);

        // 2. 各源脱敏数据块：结构先行，本月先给空块（W5 填充真实摘要）
        Map<String, Object> dataBlocks = new LinkedHashMap<>();
        for (SourceTypeEnum type : SourceTypeEnum.values()) {
            dataBlocks.put(type.getCode(), new LinkedHashMap<String, Object>());
        }
        vo.setDataBlocks(dataBlocks);

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

        // 本期预留：仅做参数校验与日志记录，不落库（W3 接入埋点后按需落表）
        for (MultiSourceEventDTO event : events) {
            int fieldCount = event.getEventData() == null ? 0 : event.getEventData().size();
            log.info("📥 [多源事件上报] 用户: {}, 类型: {}, 字段数: {}, 客户端时间戳: {}",
                    userId, event.getEventType(), fieldCount, event.getTimestamp());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("accepted", events.size());
        return Result.success(data);
    }
}

package com.loan.service.impl;

import com.loan.entity.DataSourceAuthorization;
import com.loan.entity.dto.DataSourceAuthorizeDTO;
import com.loan.entity.enums.AuthStatusEnum;
import com.loan.entity.enums.SourceTypeEnum;
import com.loan.entity.vo.DataSourceAuthorizationVO;
import com.loan.entity.vo.Result;
import com.loan.mapper.DataSourceAuthorizationMapper;
import com.loan.service.DataSourceService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.UUID;

/**
 * 多数据源授权服务实现（模拟授权流程，落授权留痕表）
 */
@Service
public class DataSourceServiceImpl implements DataSourceService {

    private static final Logger log = LoggerFactory.getLogger(DataSourceServiceImpl.class);

    /**
     * 模拟授权有效期：90 天
     */
    private static final int AUTH_VALID_DAYS = 90;

    @Resource
    private DataSourceAuthorizationMapper authorizationMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> authorize(String userId, DataSourceAuthorizeDTO dto) {
        String sourceType = normalizeSourceType(dto.getSourceType());
        if (sourceType == null) {
            return Result.error(400, "不支持的数据源类型：" + dto.getSourceType());
        }

        Date now = new Date();
        Date expiryAt = plusDays(now, AUTH_VALID_DAYS);
        String mockToken = buildMockToken(sourceType);

        DataSourceAuthorization existing =
                authorizationMapper.selectByUserIdAndSourceType(userId, sourceType);

        if (existing == null) {
            DataSourceAuthorization record = new DataSourceAuthorization();
            record.setUserId(userId);
            record.setSourceType(sourceType);
            record.setAuthStatus(AuthStatusEnum.AUTHORIZED.getCode());
            record.setAuthToken(mockToken);
            record.setScopeJson(dto.getScopeJson());
            record.setAuthorizedAt(now);
            record.setExpiryAt(expiryAt);
            record.setCreateTime(now);
            record.setUpdateTime(now);
            authorizationMapper.insert(record);
            log.info("✅ 用户 {} 授权数据源 {}({}), 有效期至 {}",
                    userId, SourceTypeEnum.getByCode(sourceType).getDesc(), sourceType, expiryAt);
        } else if (!AuthStatusEnum.AUTHORIZED.getCode().equals(existing.getAuthStatus())) {
            authorizationMapper.updateAuthStatus(userId, sourceType,
                    AuthStatusEnum.AUTHORIZED.getCode(), mockToken, now, expiryAt);
            log.info("✅ 用户 {} 重新授权数据源 {}", userId, sourceType);
        } else {
            log.info("ℹ️ 用户 {} 数据源 {} 已处于授权状态，幂等返回", userId, sourceType);
        }

        return Result.success(buildVO(authorizationMapper
                .selectByUserIdAndSourceType(userId, sourceType)));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> revoke(String userId, DataSourceAuthorizeDTO dto) {
        String sourceType = normalizeSourceType(dto.getSourceType());
        if (sourceType == null) {
            return Result.error(400, "不支持的数据源类型：" + dto.getSourceType());
        }

        DataSourceAuthorization existing =
                authorizationMapper.selectByUserIdAndSourceType(userId, sourceType);
        if (existing == null) {
            return Result.error(404, "未找到该数据源的授权记录");
        }

        // 留痕原则：撤销只改状态，保留原授权时间/有效期/凭证，便于审计追溯
        authorizationMapper.updateAuthStatus(userId, sourceType,
                AuthStatusEnum.REVOKED.getCode(),
                existing.getAuthToken(), existing.getAuthorizedAt(), existing.getExpiryAt());
        log.info("✅ 用户 {} 已撤销数据源 {} 的授权（保留历史留痕）", userId, sourceType);

        return Result.success("授权已撤销");
    }

    @Override
    public Result<List<DataSourceAuthorizationVO>> listAuthorizations(String userId) {
        return Result.success(queryAuthorizationVOs(userId));
    }

    @Override
    public List<DataSourceAuthorizationVO> queryAuthorizationVOs(String userId) {
        List<DataSourceAuthorization> records = authorizationMapper.selectByUserId(userId);
        List<DataSourceAuthorizationVO> result = new ArrayList<>();

        // 以"已存在的记录"为准
        if (records != null) {
            for (DataSourceAuthorization record : records) {
                result.add(buildVO(record));
            }
        }

        // 未授权的数据源补齐 PENDING 占位（结构先行，便于 App 授权中心直接渲染）
        for (SourceTypeEnum type : SourceTypeEnum.values()) {
            boolean exists = result.stream()
                    .anyMatch(vo -> type.getCode().equals(vo.getSourceType()));
            if (!exists) {
                DataSourceAuthorizationVO pending = new DataSourceAuthorizationVO();
                pending.setSourceType(type.getCode());
                pending.setSourceName(type.getDesc());
                pending.setAuthStatus(AuthStatusEnum.PENDING.getCode());
                pending.setAuthStatusDesc(AuthStatusEnum.PENDING.getDesc());
                pending.setAuthorized(false);
                result.add(pending);
            }
        }
        return result;
    }

    // ========== 私有方法 ==========

    /**
     * 归一化并校验数据源类型
     */
    private String normalizeSourceType(String sourceType) {
        if (sourceType == null) {
            return null;
        }
        String normalized = sourceType.trim().toLowerCase();
        return SourceTypeEnum.isSupported(normalized) ? normalized : null;
    }

    /**
     * 构建脱敏模拟授权凭证（不含任何真实敏感信息）
     */
    private String buildMockToken(String sourceType) {
        return "MOCK-" + sourceType + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private Date plusDays(Date base, int days) {
        Calendar calendar = Calendar.getInstance();
        calendar.setTime(base);
        calendar.add(Calendar.DAY_OF_MONTH, days);
        return calendar.getTime();
    }

    private DataSourceAuthorizationVO buildVO(DataSourceAuthorization record) {
        if (record == null) {
            return null;
        }
        DataSourceAuthorizationVO vo = new DataSourceAuthorizationVO();
        vo.setSourceType(record.getSourceType());
        SourceTypeEnum typeEnum = SourceTypeEnum.getByCode(record.getSourceType());
        vo.setSourceName(typeEnum != null ? typeEnum.getDesc() : record.getSourceType());
        vo.setAuthStatus(record.getAuthStatus());
        vo.setAuthStatusDesc(AuthStatusEnum.getDescByCode(record.getAuthStatus()));
        vo.setAuthorized(AuthStatusEnum.AUTHORIZED.getCode().equals(record.getAuthStatus()));
        vo.setAuthorizedAt(record.getAuthorizedAt());
        vo.setExpiryAt(record.getExpiryAt());
        return vo;
    }
}

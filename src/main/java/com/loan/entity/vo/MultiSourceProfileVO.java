package com.loan.entity.vo;

import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * 多源数据脱敏摘要 VO（结构先行，W5 由两端展示消费）
 */
@Data
public class MultiSourceProfileVO {

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 各数据源授权状态
     */
    private List<DataSourceAuthorizationVO> authorizations;

    /**
     * 各数据源脱敏数据块：sourceType -> 字段摘要
     * （W5 填充真实摘要，本期返回空块，保证结构先行）
     */
    private Map<String, Object> dataBlocks;

    /**
     * 当前已注册的数据源类型（来自注册表）
     */
    private List<String> registeredSources;

    /**
     * 数据口径版本
     */
    private String dataVersion;
}

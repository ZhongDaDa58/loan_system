package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 多源标准化数据实体
 * 对应数据库表：user_multi_source_data
 * 说明：存放 Provider 拉取并标准化后的数据；特征宽表化留待 W5。
 */
@Data
public class UserMultiSourceData {

    private Long id;

    /**
     * 用户ID
     */
    private String userId;

    /**
     * 数据源类型：credit/operator/social/device（见 SourceTypeEnum）
     */
    private String sourceType;

    /**
     * 标准化数据载荷（JSON），字段口径见《多源数据.md》
     */
    private String dataJson;

    /**
     * 数据口径版本（对齐模型/特征版本）
     */
    private String dataVersion;

    /**
     * 拉取状态：SUCCESS/FAILED/STALE
     */
    private String fetchStatus;

    /**
     * 最近拉取时间
     */
    private Date fetchedAt;

    private Date createTime;

    private Date updateTime;
}

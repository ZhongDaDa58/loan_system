package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 多数据源授权留痕实体
 * 对应数据库表：data_source_authorization
 */
@Data
public class DataSourceAuthorization {

    private Long id;

    /**
     * 用户ID（关联 sys_user.user_id）
     */
    private String userId;

    /**
     * 数据源类型：credit/operator/social/device（见 SourceTypeEnum）
     */
    private String sourceType;

    /**
     * 授权状态：PENDING/AUTHORIZED/REVOKED/EXPIRED（见 AuthStatusEnum）
     */
    private String authStatus;

    /**
     * 模拟授权凭证（脱敏存储，不落明文敏感信息）
     */
    private String authToken;

    /**
     * 授权范围（JSON）
     */
    private String scopeJson;

    /**
     * 授权时间
     */
    private Date authorizedAt;

    /**
     * 授权有效期至
     */
    private Date expiryAt;

    private Date createTime;

    private Date updateTime;
}

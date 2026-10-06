package com.loan.entity.vo;

import lombok.Data;

import java.util.Date;

/**
 * 数据源授权状态 VO（脱敏展示）
 */
@Data
public class DataSourceAuthorizationVO {

    /**
     * 数据源类型：credit/operator/social/device
     */
    private String sourceType;

    /**
     * 数据源中文名
     */
    private String sourceName;

    /**
     * 授权状态：PENDING/AUTHORIZED/REVOKED/EXPIRED
     */
    private String authStatus;

    /**
     * 授权状态描述
     */
    private String authStatusDesc;

    /**
     * 是否已授权
     */
    private Boolean authorized;

    /**
     * 授权时间（脱敏，仅返回展示所需）
     */
    private Date authorizedAt;

    /**
     * 授权有效期至
     */
    private Date expiryAt;
}

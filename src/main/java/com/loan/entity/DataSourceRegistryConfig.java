package com.loan.entity;

import lombok.Data;

import java.util.Date;

/**
 * 数据源 Provider 注册配置实体
 * 对应数据库表：data_source_registry
 */
@Data
public class DataSourceRegistryConfig {

    private Long id;

    /**
     * 数据源类型：credit/operator/social/device（见 SourceTypeEnum）
     */
    private String sourceType;

    /**
     * Provider 实现类名或 Bean 名
     */
    private String providerName;

    /**
     * 是否启用：1=启用 0=停用
     */
    private Integer enabled;

    /**
     * 优先级（同源多 Provider 时生效）
     */
    private Integer priority;

    /**
     * 熔断状态：CLOSED/OPEN/HALF_OPEN
     */
    private String circuitStatus;

    /**
     * 扩展配置（JSON）
     */
    private String configJson;

    /**
     * 最近健康检查时间
     */
    private Date lastHealthAt;

    private Date createTime;

    private Date updateTime;
}

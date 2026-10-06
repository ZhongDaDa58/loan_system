package com.loan.spi;

import lombok.Data;

import java.util.Date;
import java.util.Map;

/**
 * 多源数据载体
 * Provider.fetch 的统一返回结构，供特征工程层/评分卡消费。
 */
@Data
public class MultiSourceData {

    /**
     * 数据源类型：credit/operator/social/device
     */
    private String sourceType;

    /**
     * 标准化字段（键名遵循《多源数据.md》）
     */
    private Map<String, Object> fields;

    /**
     * 拉取时间
     */
    private Date fetchedAt;

    public MultiSourceData() {
    }

    public MultiSourceData(String sourceType, Map<String, Object> fields, Date fetchedAt) {
        this.sourceType = sourceType;
        this.fields = fields;
        this.fetchedAt = fetchedAt;
    }
}

package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * 数据源授权请求 DTO
 */
@Data
public class DataSourceAuthorizeDTO {

    /**
     * 数据源类型：credit/operator/social/device
     */
    @NotBlank(message = "数据源类型不能为空")
    private String sourceType;

    /**
     * 授权范围说明（可选，JSON 字符串）
     */
    private String scopeJson;
}

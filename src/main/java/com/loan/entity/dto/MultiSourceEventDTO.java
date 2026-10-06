package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

/**
 * 多源事件上报 DTO（App 端设备/行为事件预留）
 */
@Data
public class MultiSourceEventDTO {

    /**
     * 事件来源类型：device/behavior/location
     */
    @NotBlank(message = "事件类型不能为空")
    private String eventType;

    /**
     * 事件载荷（键名见《多源数据.md》）
     */
    private Map<String, Object> eventData;

    /**
     * 客户端事件时间戳（毫秒）
     */
    private Long timestamp;
}

package com.loan.entity.vo;

import lombok.Data;

@Data
public class KycResultVO {
    private Boolean success;
    private String message;
    private Object data; // 存放OCR结果或人脸比对分数
}

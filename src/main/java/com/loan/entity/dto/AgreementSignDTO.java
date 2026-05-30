// AgreementSignDTO.java
package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class AgreementSignDTO {
    @NotNull(message = "协议ID不能为空")
    private Long agreementId;

    @NotBlank(message = "签名图片URL不能为空")
    private String signatureImageUrl;

    private String deviceInfo; // 设备信息（前端传递）
}

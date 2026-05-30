
package com.loan.entity.dto;

import lombok.Data;
import jakarta.validation.constraints.NotBlank;

@Data
public class SignatureUploadDTO {
    @NotBlank(message = "签名数据不能为空")
    private String signatureImage; // ⭐ 必须和前端 JSON 里的 key 一模一样
}

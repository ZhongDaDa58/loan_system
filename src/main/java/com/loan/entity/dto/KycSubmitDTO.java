package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KycSubmitDTO {
    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCardNumber;

    @NotBlank(message = "身份证正面照不能为空")
    private String frontUrl;

    @NotBlank(message = "人脸抓拍图路径不能为空")
    private String faceCaptureUrl;
}

package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class KycOcrDTO {
    @NotBlank(message = "图片Base64不能为空")
    private String imageBase64;

    private String side; // front / back
}

package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BindCardDTO {

    @NotBlank(message = "银行卡号不能为空")
    private String cardNumber;

    @NotBlank(message = "预留手机号不能为空")
    private String phone;
}
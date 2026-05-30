package com.loan.entity.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class FourFactorVerifyDTO {
    @NotBlank(message = "姓名不能为空")
    private String realName;

    @NotBlank(message = "身份证号不能为空")
    private String idCardNumber;

    @NotBlank(message = "银行卡号不能为空")
    private String bankCardNo;

    @NotBlank(message = "预留手机号不能为空")
    private String phone;
}

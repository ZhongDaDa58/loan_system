package com.loan.entity.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreditScoreUpdateDTO {

    @NotNull(message = "信用分不能为空")
    @Min(value = 300, message = "信用分最低为300")
    @Max(value = 950, message = "信用分最高为950")
    private Integer creditScore;
}

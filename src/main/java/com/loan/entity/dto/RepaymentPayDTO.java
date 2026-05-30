package com.loan.entity.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RepaymentPayDTO {
    @NotNull(message = "还款计划ID不能为空")
    private Long planId;

    @NotNull(message = "银行卡ID不能为空")
    private Long cardId;
}

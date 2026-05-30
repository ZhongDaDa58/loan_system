package com.loan.entity.dto;
import com.fasterxml.jackson.annotation.JsonFormat;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class RepaymentSubmitDTO {
    @NotBlank(message = "还款明细ID不能为空")
    private String repaymentId; // 还款明细ID（每月还款记录ID）

    @NotNull(message = "还款金额不能为空")
    @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "0.00")
    private BigDecimal repaymentAmount;

    private Long paymentCardId;


}
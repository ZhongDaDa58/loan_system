package com.loan.entity.dto;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class LoanApplyDTO {

    @NotBlank(message = "产品ID不能为空")
    private String productId;       // 产品ID

    @NotNull(message = "申请金额不能为空")
    @DecimalMin(value = "0.01", message = "申请金额必须大于0")
    private BigDecimal applyAmount; // 申请金额

    @NotNull(message = "申请期限不能为空")
    @Min(value = 1, message = "申请期限必须大于0")
    private Integer applyTerm;      // 申请期限（月）

    private Long disbursementCardId;
}

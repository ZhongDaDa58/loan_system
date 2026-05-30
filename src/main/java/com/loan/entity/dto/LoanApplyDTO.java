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

    @NotNull(message = "年龄不能为空")
    @Min(value = 18, message = "年龄必须大于等于18岁")
    private Integer age;

    @NotNull(message = "债务比率不能为空")
    @DecimalMin(value = "0", message = "债务比率不能为负数")
    private BigDecimal debtRatio;

    private BigDecimal monthlyIncome;

    @NotNull(message = "信用额度数量不能为空")
    @Min(value = 0, message = "信用额度数量不能为负数")
    private Integer creditLines;

    @NotNull(message = "家属人数不能为空")
    @Min(value = 0, message = "家属人数不能为负数")
    private Integer dependents;

    @NotNull(message = "资产总额不能为空")
    @DecimalMin(value = "0", message = "资产总额不能为负数")
    private BigDecimal revolvingUtil;

    private Long disbursementCardId;
}
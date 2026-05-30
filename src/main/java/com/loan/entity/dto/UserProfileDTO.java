package com.loan.entity.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;

@Data
public class UserProfileDTO {

    @NotNull(message = "循环信贷使用率不能为空")
    @DecimalMin(value = "0", message = "循环信贷使用率不能为负数")
    private BigDecimal revolvingUtil;

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

    public BigDecimal getRevolvingUtil() {
        return revolvingUtil;
    }
}
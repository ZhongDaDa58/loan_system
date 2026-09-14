package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OverdueAmountDistVO {
    private String range;    // 1万以下 / 1-3万 / 3-5万 / 5万以上
    private BigDecimal amount;
}

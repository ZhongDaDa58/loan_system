package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class TrendPointVO {
    private String month;        // YYYY-MM
    private BigDecimal amount;
    private Long count;
}

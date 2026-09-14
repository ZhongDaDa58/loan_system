package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OverdueSummaryVO {
    private BigDecimal currentOverdueRate;   // 百分比，如 3.8
    private BigDecimal overdueAmount;
    private Integer overdueCustomers;
    private Integer avgOverdueDays;
}

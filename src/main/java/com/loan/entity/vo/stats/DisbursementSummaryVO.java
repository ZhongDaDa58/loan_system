package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DisbursementSummaryVO {
    private BigDecimal totalAmount;
    private Long totalCount;
    private BigDecimal avgAmount;
    private BigDecimal todayAmount;
}

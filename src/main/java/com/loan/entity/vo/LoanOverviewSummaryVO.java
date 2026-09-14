package com.loan.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanOverviewSummaryVO {
    private BigDecimal totalBorrowed;
    private BigDecimal pendingRepayment;
    private Integer overdueCount;
    private Integer activeLoanCount;
    private Integer totalLoanCount;
}

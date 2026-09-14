package com.loan.entity.vo.loanDetail;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanHistorySummaryVO {
    private Integer totalApplications;
    private Integer approvedCount;
    private Integer rejectedCount;
    private BigDecimal totalBorrowed;
    private BigDecimal totalRepaid;
    private Integer overdueCount;
}

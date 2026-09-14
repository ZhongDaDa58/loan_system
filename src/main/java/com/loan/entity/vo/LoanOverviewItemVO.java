package com.loan.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanOverviewItemVO {
    private String applicationId;
    private String productName;
    private BigDecimal applyAmount;
    private Integer applyTerm;
    private String applyTime;
    private String status;          // repaying / cleared / overdue / pending
    private String statusDesc;
    private BigDecimal remainingAmount;
    private Integer remainingTerm;
    private String nextRepaymentDate;
    private BigDecimal nextRepaymentAmount;
    private Integer overdueDays;
    private BigDecimal overdueAmount;
    private BigDecimal interestRate;
}

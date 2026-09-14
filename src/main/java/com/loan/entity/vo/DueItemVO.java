package com.loan.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DueItemVO {
    private String repaymentId;
    private String applicationId;
    private String productName;
    private Integer term;
    private String dueDate;
    private BigDecimal repaymentAmount;
    private BigDecimal principal;
    private BigDecimal interest;
    private String status;       // pending / overdue
    private String statusDesc;
    private Integer overdueDays;
    private BigDecimal overduePenalty;
    private BigDecimal totalDue;
}

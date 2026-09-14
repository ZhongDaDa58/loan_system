package com.loan.entity.vo;

import lombok.Data;

import java.math.BigDecimal;
import java.util.Date;

@Data
public class UpcomingRepaymentVO {
    private String repaymentId;
    private String planId;
    private Integer term;
    private BigDecimal repaymentAmount;
    private Date dueDate;
    private String userId;
}

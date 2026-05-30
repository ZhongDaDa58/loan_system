package com.loan.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class MonthlyRepayment {
    private String repaymentId;     // 还款明细ID
    private String planId;          // 还款计划ID
    private Integer term;           // 期数
    private BigDecimal principal;   // 当期本金
    private BigDecimal interest;    // 当期利息
    private BigDecimal repaymentAmount; // 当期还款金额
    private Date dueDate;           // 到期还款日
    private String repaymentStatus; // 还款状态
    private Date repaymentTime;     // 实际还款时间
}
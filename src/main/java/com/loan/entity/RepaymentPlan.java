package com.loan.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class RepaymentPlan {
    private String planId;          // 还款计划ID
    private String applicationId;   // 申请ID
    private String userId;          // 用户ID
    private BigDecimal totalAmount; // 总还款金额
    private BigDecimal totalPrincipal; // 总本金
    private BigDecimal totalInterest; // 总利息
    private String repaymentType;   // 还款方式
    private Integer termCount;      // 总期数
    private Date createTime;        // 创建时间
}

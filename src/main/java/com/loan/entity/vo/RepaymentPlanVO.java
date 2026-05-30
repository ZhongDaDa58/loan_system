package com.loan.entity.vo;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;
import java.util.List;

@Data
public class RepaymentPlanVO {
    private String planId;          // 还款计划ID
    private String applicationId;   // 申请ID
    private BigDecimal totalAmount; // 总还款金额
    private BigDecimal totalPrincipal; // 总本金
    private BigDecimal totalInterest; // 总利息
    private String repaymentType;   // 还款方式
    private String repaymentTypeDesc; // 还款方式描述
    private Integer termCount;      // 总期数
    private Date createTime;        // 创建时间
    private List<MonthlyRepaymentVO> monthlyRepaymentList; // 每月还款明细
}
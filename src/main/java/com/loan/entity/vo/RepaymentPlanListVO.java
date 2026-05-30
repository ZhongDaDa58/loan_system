package com.loan.entity.vo;

import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class RepaymentPlanListVO {
    private String planId;          // 还款计划 ID
    private String applicationId;   // 申请 ID
    private String userId;          // 用户 ID
    private String userName;        // 用户姓名
    private String userPhone;       // 用户手机号
    private BigDecimal totalAmount; // 总还款金额
    private BigDecimal totalPrincipal; // 总本金
    private BigDecimal totalInterest; // 总利息
    private String repaymentType;   // 还款方式
    private String repaymentTypeDesc; // 还款方式描述
    private Integer termCount;      // 总期数
    private Date createTime;        // 创建时间
    private Boolean hasOverdue;     // 是否有逾期
    private Integer overdueCount;   // 逾期期数
}

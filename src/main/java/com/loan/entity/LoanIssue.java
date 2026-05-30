package com.loan.entity;
import lombok.Data;
import java.math.BigDecimal;
import java.util.Date;

@Data
public class LoanIssue {
    private String issueId;         // 放款记录ID
    private String applicationId;   // 申请ID
    private String userId;          // 用户ID
    private BigDecimal issueAmount; // 放款金额
    private String issueStatus;     // 放款状态
    private Date issueTime;         // 放款时间
    private Long disbursementCardId;// 放款银行卡ID
}

package com.loan.entity.vo.loanDetail;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanHistoryRecordVO {
    private String applicationId;
    private String productName;
    private BigDecimal amount;
    private Integer term;
    private String status;          // completed / repaying / rejected / cancelled
    private String applyTime;       // yyyy-MM-dd HH:mm:ss
    private String disbursementTime; // 可为 null
}

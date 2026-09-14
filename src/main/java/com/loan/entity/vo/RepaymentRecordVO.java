package com.loan.entity.vo;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class RepaymentRecordVO {
    private String repaymentId;
    private String loanId;
    private Integer period;
    private BigDecimal amount;
    private String dueDate;
    private String repayDate;
    private String status;
}

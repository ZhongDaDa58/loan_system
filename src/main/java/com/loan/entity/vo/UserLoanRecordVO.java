package com.loan.entity.vo;

import lombok.Data;
import java.math.BigDecimal;

@Data
public class UserLoanRecordVO {
    private String applicationId;
    private String productName;
    private BigDecimal applyAmount;
    private Integer applyTerm;
    private String status;
    private String applyTime;
}

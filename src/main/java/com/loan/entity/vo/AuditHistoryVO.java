package com.loan.entity.vo;

import lombok.Data;
import java.util.Date;

@Data
public class AuditHistoryVO {
    private String auditId;
    private String applicationId;
    private String auditorId;
    private String auditResult;
    private String auditOpinion;
    private Date auditTime;

    // related application fields for display
    private String productName;
    private java.math.BigDecimal applyAmount;
    private Integer applyTerm;
    private String userId;
    private String userName;
}


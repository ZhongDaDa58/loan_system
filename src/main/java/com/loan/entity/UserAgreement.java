package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserAgreement {
    private Long agreementId;
    private String agreementType; // PRIVACY_POLICY, USER_SERVICE, CREDIT_AUTH, E_SIGNATURE
    private String version;       // v1.0, v1.1, v2.0
    private String title;
    private String content;       // HTML内容
    private String summary;
    private Integer isCurrent;    // 0-否, 1-是
    private Date effectiveDate;
    private Date createTime;
    private Date updateTime;
}

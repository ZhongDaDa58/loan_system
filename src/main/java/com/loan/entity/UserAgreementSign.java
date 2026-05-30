package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserAgreementSign {
    private Long signId;
    private String userId;
    private Long agreementId;
    private String agreementType;   // 冗余字段
    private String agreementVersion; // 冗余字段
    private String signatureImageUrl;
    private Date signTime;
    private String ipAddress;
    private String deviceInfo;
    private Integer isValid;        // 0-已撤销, 1-有效
    private Date createTime;
}

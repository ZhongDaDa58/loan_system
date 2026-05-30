// MyAgreementSignVO.java
package com.loan.entity.vo;

import lombok.Data;
import java.util.Date;

@Data
public class MyAgreementSignVO {
    private Long signId;
    private String agreementTitle;
    private String agreementType;
    private String version;
    private Date signTime;
    private String signatureImageUrl;
    private String ipAddress;
}

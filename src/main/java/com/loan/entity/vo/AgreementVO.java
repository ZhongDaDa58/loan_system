// AgreementVO.java
package com.loan.entity.vo;

import lombok.Data;
import java.util.Date;

@Data
public class AgreementVO {
    private Long agreementId;
    private String agreementType;
    private String agreementTypeDesc; // 隐私政策、用户服务协议等
    private String version;
    private String title;
    private String summary;
    private String content;
    private Date effectiveDate;
    private Boolean hasSigned; // 当前用户是否已签署
    private Date signTime;     // 如果已签署，显示签署时间
}

package com.loan.entity;

import lombok.Data;
import java.util.Date;

@Data
public class UserIdentity {
    private Long id;
    private String userId;
    private String userRealName;
    private String idCardNumber;
    private String idCardFrontUrl;
    private String faceCaptureUrl;
    private Integer verifyStatus;
    private Date createTime;
    private Date updateTime;
}

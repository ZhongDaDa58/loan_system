package com.loan.entity.vo;

import lombok.Data;

@Data
public class UserVerificationVO {
    private String realName;
    private String idCard;
    private String verifyTime;
    private String verifyMethod;
    private String idCardFront;
    private String idCardBack;
    private String facePhoto;
}

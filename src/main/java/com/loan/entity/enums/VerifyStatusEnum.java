package com.loan.entity.enums;

import lombok.Getter;

@Getter
public enum VerifyStatusEnum {
    VERIFIED("verified", "已认证"),
    UNVERIFIED("unverified", "未认证"),
    VERIFYING("verifying", "认证中");

    private final String code;
    private final String desc;

    VerifyStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static VerifyStatusEnum getByCode(String code) {
        for (VerifyStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}

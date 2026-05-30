package com.loan.entity.enums;

import lombok.Getter;

@Getter
public enum AgreementTypeEnum {
    PRIVACY_POLICY("PRIVACY_POLICY", "隐私政策"),
    USER_SERVICE("USER_SERVICE", "用户服务协议"),
    CREDIT_AUTH("CREDIT_AUTH", "征信授权书"),
    E_SIGNATURE("E_SIGNATURE", "电子签名协议");

    private final String code;
    private final String desc;

    AgreementTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    public static String getDescByCode(String code) {
        for (AgreementTypeEnum type : values()) {
            if (type.getCode().equals(code)) {
                return type.getDesc();
            }
        }
        return "未知协议";
    }
}

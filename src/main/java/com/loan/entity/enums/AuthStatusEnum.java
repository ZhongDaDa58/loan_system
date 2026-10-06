package com.loan.entity.enums;

import lombok.Getter;

/**
 * 多源数据授权状态枚举（授权留痕）
 */
@Getter
public enum AuthStatusEnum {

    PENDING("PENDING", "待授权"),
    AUTHORIZED("AUTHORIZED", "已授权"),
    REVOKED("REVOKED", "已撤销"),
    EXPIRED("EXPIRED", "已过期");

    private final String code;
    private final String desc;

    AuthStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据code获取枚举
    public static AuthStatusEnum getByCode(String code) {
        for (AuthStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }

    // 获取描述（未知状态返回原值）
    public static String getDescByCode(String code) {
        AuthStatusEnum status = getByCode(code);
        return status != null ? status.getDesc() : code;
    }
}

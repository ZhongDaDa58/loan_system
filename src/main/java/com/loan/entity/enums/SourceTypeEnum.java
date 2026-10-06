package com.loan.entity.enums;

import lombok.Getter;

/**
 * 数据源类型枚举（多数据源融合）
 * 与《多源数据.md》的数据源清单保持一致
 */
@Getter
public enum SourceTypeEnum {

    CREDIT("credit", "央行征信"),
    OPERATOR("operator", "运营商数据"),
    SOCIAL("social", "社保/公积金"),
    DEVICE("device", "设备指纹");

    private final String code;
    private final String desc;

    SourceTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据code获取枚举
    public static SourceTypeEnum getByCode(String code) {
        for (SourceTypeEnum type : values()) {
            if (type.code.equals(code)) {
                return type;
            }
        }
        return null;
    }

    // 是否为受支持的合法数据源
    public static boolean isSupported(String code) {
        return getByCode(code) != null;
    }
}

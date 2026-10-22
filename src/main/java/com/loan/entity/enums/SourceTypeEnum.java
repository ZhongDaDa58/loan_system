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
    DEVICE("device", "设备指纹"),
    /** App 端行为埋点（由客户端直接上报，无对应授权/同步流程） */
    BEHAVIOR("behavior", "行为埋点"),
    /**
     * 位置数据（阶段 C3 新增）
     *
     * <p>App 端「定位验证」上报的经纬度。<b>仅用于第一层交叉验证与规则上下文，
     * 不作为模型入模特征</b>（设计文档 §3.5 红线：原始瞬时值留规则层）。
     */
    LOCATION("location", "位置数据");

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

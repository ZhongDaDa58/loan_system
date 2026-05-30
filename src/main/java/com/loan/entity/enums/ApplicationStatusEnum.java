package com.loan.entity.enums;
import lombok.Getter;

@Getter
public enum ApplicationStatusEnum {
    PENDING("pending", "待审核"),
    APPROVED("approved", "审核通过"),
    REJECTED("rejected", "拒绝"),
    SUPPLEMENT("supplement", "需补充材料"),
    ISSUED("issued", "已放款");

    private final String code;
    private final String desc;

    ApplicationStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }

    // 根据code获取枚举
    public static ApplicationStatusEnum getByCode(String code) {
        for (ApplicationStatusEnum status : values()) {
            if (status.code.equals(code)) {
                return status;
            }
        }
        return null;
    }
}
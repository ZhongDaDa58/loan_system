package com.loan.entity.enums;
import lombok.Getter;

@Getter
public enum AuditResultEnum {
    APPROVED("approved", "审核通过"),
    REJECTED("rejected", "拒绝"),
    SUPPLEMENT("supplement", "需补充材料");

    private final String code;
    private final String desc;

    AuditResultEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
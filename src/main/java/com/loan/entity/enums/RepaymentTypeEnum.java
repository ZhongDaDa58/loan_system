package com.loan.entity.enums;
import lombok.Getter;

@Getter
public enum RepaymentTypeEnum {
    EQUAL_PRINCIPAL_INTEREST("equal_principal_interest", "等额本息"),
    EQUAL_PRINCIPAL("equal_principal", "等额本金");

    private final String code;
    private final String desc;

    RepaymentTypeEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
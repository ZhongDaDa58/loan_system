package com.loan.entity.enums;
import lombok.Getter;

@Getter
public enum RepaymentStatusEnum {
    UNPAID("unpaid", "未还款"),
    PAID("paid", "已还款"),
    OVERDUE("overdue", "逾期");

    private final String code;
    private final String desc;

    RepaymentStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
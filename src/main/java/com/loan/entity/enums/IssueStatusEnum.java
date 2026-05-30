package com.loan.entity.enums;
import lombok.Getter;

@Getter
public enum IssueStatusEnum {
    SUCCESS("success", "放款成功"),
    FAIL("fail", "放款失败");

    private final String code;
    private final String desc;

    IssueStatusEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
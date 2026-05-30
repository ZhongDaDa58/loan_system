package com.loan.entity.enums;

import lombok.Getter;

@Getter
public enum ScorecardResultEnum {
    AUTO_PASS("auto_pass", "自动通过"),
    MANUAL_REVIEW("manual_review", "转人工审核"),
    AUTO_REJECT("auto_reject", "自动拒绝");

    private final String code;
    private final String desc;

    ScorecardResultEnum(String code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}

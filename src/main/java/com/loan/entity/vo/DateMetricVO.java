package com.loan.entity.vo;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class DateMetricVO {
    /** 日期，格式 YYYY-MM-DD */
    private String date;

    /** 值（可以表示计数或比率等，用 BigDecimal 表示以兼容两者） */
    private BigDecimal value;
}


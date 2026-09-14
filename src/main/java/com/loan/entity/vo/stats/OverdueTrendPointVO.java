package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class OverdueTrendPointVO {
    private String month;         // YYYY-MM
    private BigDecimal overdueRate; // 百分比
}

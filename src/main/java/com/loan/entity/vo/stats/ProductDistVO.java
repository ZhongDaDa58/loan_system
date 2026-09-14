package com.loan.entity.vo.stats;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class ProductDistVO {
    private String productName;
    private BigDecimal amount;
    private Long count;
}

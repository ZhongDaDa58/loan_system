package com.loan.entity.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanProductEditDTO {
    private String productName;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer minTerm;
    private Integer maxTerm;
    private BigDecimal interestRate;
    private Integer autoPassScore;
    private Integer manualReviewScore;
    // Note: intentionally no 'status' field — this API must not allow changing product status
}


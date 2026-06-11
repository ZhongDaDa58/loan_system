package com.loan.entity.dto;

import lombok.Data;

import java.math.BigDecimal;

@Data
public class LoanProductCreateDTO {
    private String productName;
    private BigDecimal minAmount;
    private BigDecimal maxAmount;
    private Integer minTerm;
    private Integer maxTerm;
    private BigDecimal interestRate;
    private Integer autoPassScore;
    private Integer manualReviewScore;
}


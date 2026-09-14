package com.loan.entity.vo.stats;

import lombok.Data;

@Data
public class AmountDistVO {
    private String range;   // 0-1万 / 1-3万 / ...
    private Long count;
}

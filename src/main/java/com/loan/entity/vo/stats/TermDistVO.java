package com.loan.entity.vo.stats;

import lombok.Data;

@Data
public class TermDistVO {
    private Integer term;   // 3 / 6 / 12 / 24 / 36
    private Long count;
}

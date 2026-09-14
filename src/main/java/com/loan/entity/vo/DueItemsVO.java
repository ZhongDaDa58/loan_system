package com.loan.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DueItemsVO {
    private Integer totalDue;
    private Integer overdueCount;
    private List<DueItemVO> items;
}

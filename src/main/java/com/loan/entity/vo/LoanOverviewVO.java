package com.loan.entity.vo;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoanOverviewVO {
    private LoanOverviewSummaryVO summary;
    private List<LoanOverviewItemVO> loanList;
}

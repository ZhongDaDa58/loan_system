package com.loan.entity.vo.loanDetail;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoanHistoryVO {
    private LoanHistorySummaryVO summary;
    private List<LoanHistoryRecordVO> records;
}

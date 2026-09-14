package com.loan.entity.vo.stats;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DisbursementStatsVO {
    private DisbursementSummaryVO summary;
    private List<TrendPointVO> trend;
    private List<ProductDistVO> productDistribution;
    private List<AmountDistVO> amountDistribution;
    private List<TermDistVO> termDistribution;
}

package com.loan.entity.vo.stats;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OverdueStatsVO {
    private OverdueSummaryVO summary;
    private List<OverdueTrendPointVO> trend;
    private List<OverdueAmountDistVO> amountDistribution;
}

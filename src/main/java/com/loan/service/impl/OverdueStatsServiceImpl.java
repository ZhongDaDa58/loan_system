package com.loan.service.impl;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.*;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.service.OverdueStatsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class OverdueStatsServiceImpl implements OverdueStatsService {

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Override
    public Result<OverdueStatsVO> getStats(String startDate, String endDate) {
        // 1. 核心指标
        OverdueSummaryVO raw = monthlyRepaymentMapper.selectOverdueSummary(startDate, endDate);
        Long totalDue = monthlyRepaymentMapper.countTotalDueWithinPeriod(startDate, endDate);

        OverdueSummaryVO summary = new OverdueSummaryVO();
        summary.setOverdueAmount(raw.getOverdueAmount());
        summary.setOverdueCustomers(raw.getOverdueCustomers());
        summary.setAvgOverdueDays(raw.getAvgOverdueDays());
        // 逾期率 = 逾期客户数 / 期内总应还款客户数 * 100
        BigDecimal rate = BigDecimal.ZERO;
        if (totalDue != null && totalDue > 0 && raw.getOverdueCustomers() != null && raw.getOverdueCustomers() > 0) {
            rate = BigDecimal.valueOf(raw.getOverdueCustomers())
                    .multiply(BigDecimal.valueOf(100))
                    .divide(BigDecimal.valueOf(totalDue), 1, RoundingMode.HALF_UP);
        }
        summary.setCurrentOverdueRate(rate);

        // 2. 趋势：按月统计逾期笔数 + 总应还款笔数 → 计算逾期率
        List<OverdueTrendRaw> overdueByMonth = monthlyRepaymentMapper.selectOverdueCountByMonth(startDate, endDate);
        List<TotalDueRaw> totalByMonth = monthlyRepaymentMapper.selectTotalDueCountByMonth(startDate, endDate);
        Map<String, Long> totalMap = totalByMonth.stream()
                .collect(Collectors.toMap(TotalDueRaw::getMonth, TotalDueRaw::getCount));

        List<OverdueTrendPointVO> trend = new ArrayList<>();
        for (OverdueTrendRaw o : overdueByMonth) {
            OverdueTrendPointVO p = new OverdueTrendPointVO();
            p.setMonth(o.getMonth());
            Long total = totalMap.getOrDefault(o.getMonth(), 0L);
            BigDecimal monthRate = BigDecimal.ZERO;
            if (total > 0) {
                monthRate = BigDecimal.valueOf(o.getCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(total), 1, RoundingMode.HALF_UP);
            }
            p.setOverdueRate(monthRate);
            trend.add(p);
        }

        // 3. 金额分布
        List<OverdueAmountDistVO> amountDist = monthlyRepaymentMapper.selectOverdueAmountDistribution(startDate, endDate);

        return Result.success(new OverdueStatsVO(summary, trend, amountDist));
    }

    // ===== 内部辅助类 =====

    public static class OverdueTrendRaw {
        private String month;
        private Long count;
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
    }

    public static class TotalDueRaw {
        private String month;
        private Long count;
        public String getMonth() { return month; }
        public void setMonth(String month) { this.month = month; }
        public Long getCount() { return count; }
        public void setCount(Long count) { this.count = count; }
    }
}

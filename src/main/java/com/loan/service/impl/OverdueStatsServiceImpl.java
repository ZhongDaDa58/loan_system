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

    // ==================== 第 6 步：分级看板 ====================

    @Override
    public Result<com.loan.entity.vo.stats.OverdueLevelStatsVO> getLevelStats(
            String startDate, String endDate) {

        // 1. 分级分布（补齐 M1/M2/M3 三档 —— 某个等级没有记录时 SQL 不会返回该行，
        //    前端画图需要三档都在，否则柱状图会缺柱子）
        Map<String, com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket> byLevel =
                new LinkedHashMap<>();
        for (String lv : new String[]{"M1", "M2", "M3"}) {
            com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket b =
                    new com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket();
            b.setLevel(lv);
            b.setLabel(levelLabel(lv));
            b.setCount(0);
            b.setAmount(BigDecimal.ZERO);
            b.setCountRatio(BigDecimal.ZERO);
            byLevel.put(lv, b);
        }
        for (com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket raw
                : monthlyRepaymentMapper.selectOverdueLevelDistribution()) {
            if (raw.getLevel() != null && byLevel.containsKey(raw.getLevel())) {
                byLevel.get(raw.getLevel()).setCount(raw.getCount() == null ? 0 : raw.getCount());
                byLevel.get(raw.getLevel()).setAmount(raw.getAmount() == null ? BigDecimal.ZERO : raw.getAmount());
            }
        }

        int totalCount = byLevel.values().stream().mapToInt(b -> b.getCount()).sum();
        BigDecimal totalAmount = byLevel.values().stream()
                .map(b -> b.getAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);

        // 笔数占比
        for (com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket b : byLevel.values()) {
            if (totalCount > 0) {
                b.setCountRatio(BigDecimal.valueOf(b.getCount())
                        .multiply(BigDecimal.valueOf(100))
                        .divide(BigDecimal.valueOf(totalCount), 1, RoundingMode.HALF_UP));
            }
        }

        // 2. 迁徙：期内升级事件数（这部分是准确的）
        int m1ToM2 = 0;
        int m2ToM3 = 0;
        for (com.loan.entity.vo.stats.OverdueLevelStatsVO.LevelBucket e
                : monthlyRepaymentMapper.selectLevelUpgradeEvents(startDate, endDate)) {
            int c = e.getCount() == null ? 0 : e.getCount();
            if ("M2".equals(e.getLevel())) {
                m1ToM2 = c;
            } else if ("M3".equals(e.getLevel())) {
                m2ToM3 = c;
            }
        }

        // 3. 迁徙率（近似）：期初存量 ≈ 当前存量 + 期内升走量
        //    等级只升不降，所以"期内升到 M2 的"就是"从 M1 走的"。
        int currentM1 = byLevel.get("M1").getCount();
        int currentM2 = byLevel.get("M2").getCount();
        BigDecimal m1ToM2Rate = rate(m1ToM2, currentM1 + m1ToM2);
        BigDecimal m2ToM3Rate = rate(m2ToM3, currentM2 + m2ToM3);

        com.loan.entity.vo.stats.OverdueLevelStatsVO.Migration migration =
                new com.loan.entity.vo.stats.OverdueLevelStatsVO.Migration(
                        m1ToM2, m2ToM3, m1ToM2Rate, m2ToM3Rate,
                        "升级事件数为准确值；迁徙率为近似值 —— 本项目未建历史等级快照，"
                                + "分母用「当前存量 + 期内升走量」倒推期初规模。"
                                + "跳级（M1 直接到 M3）会被计入 M2→M3 的口径误差，不建议与同业报表直接对比。");

        return Result.success(new com.loan.entity.vo.stats.OverdueLevelStatsVO(
                totalCount, totalAmount, new ArrayList<>(byLevel.values()), migration));
    }

    /** 等级展示标签 */
    private static String levelLabel(String level) {
        return switch (level) {
            case "M1" -> "M1（1-30天）";
            case "M2" -> "M2（31-60天）";
            case "M3" -> "M3（60天以上）";
            default -> level;
        };
    }

    /** 百分比，分母为 0 时返回 0（避免除零异常） */
    private static BigDecimal rate(int numerator, int denominator) {
        if (denominator <= 0) {
            return BigDecimal.ZERO;
        }
        return BigDecimal.valueOf(numerator)
                .multiply(BigDecimal.valueOf(100))
                .divide(BigDecimal.valueOf(denominator), 1, RoundingMode.HALF_UP);
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

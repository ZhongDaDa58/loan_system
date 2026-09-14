package com.loan.service.impl;

import com.loan.entity.vo.*;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.service.LoanOverviewService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class LoanOverviewServiceImpl implements LoanOverviewService {

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Override
    public Result<LoanOverviewVO> getLoanOverview(String userId) {
        // 获取用户所有贷款申请
        List<LoanOverviewItemVO> items = loanApplicationMapper.selectLoanOverview(userId);
        if (items == null || items.isEmpty()) {
            return Result.error(404, "暂无贷款记录");
        }

        // 获取用户所有还款明细
        List<RepaymentRecordVO> allRepayments = monthlyRepaymentMapper.selectRepaymentRecordByUserId(userId);
        Map<String, List<RepaymentRecordVO>> repayByLoan = allRepayments.stream()
                .collect(Collectors.groupingBy(RepaymentRecordVO::getLoanId));

        // 组装每笔贷款详情
        for (LoanOverviewItemVO item : items) {
            enrichItem(item, repayByLoan.getOrDefault(item.getApplicationId(), Collections.emptyList()));
        }

        // 计算汇总
        LoanOverviewSummaryVO summary = computeSummary(items);

        return Result.success(new LoanOverviewVO(summary, items));
    }

    private void enrichItem(LoanOverviewItemVO item, List<RepaymentRecordVO> repayments) {
        String rawStatus = item.getStatus();

        // 状态映射
        if ("pending".equals(rawStatus) || "supplement".equals(rawStatus)) {
            item.setStatus("pending");
            item.setStatusDesc("审核中");
            item.setRemainingAmount(BigDecimal.ZERO);
            item.setRemainingTerm(0);
            return;
        }

        if ("rejected".equals(rawStatus)) {
            item.setStatus("rejected");
            item.setStatusDesc("已拒绝");
            item.setRemainingAmount(BigDecimal.ZERO);
            item.setRemainingTerm(0);
            return;
        }

        if (repayments.isEmpty()) {
            // approved 但未放款
            item.setStatus("pending");
            item.setStatusDesc("待放款");
            item.setRemainingAmount(item.getApplyAmount());
            item.setRemainingTerm(item.getApplyTerm());
            return;
        }

        // 根据还款明细计算
        long paidCount = repayments.stream().filter(r -> "paid".equals(r.getStatus())).count();
        long unpaidCount = repayments.stream().filter(r -> "unpaid".equals(r.getStatus())).count();
        long overdueCount = repayments.stream().filter(r -> "overdue".equals(r.getStatus())).count();
        int totalTerms = repayments.size();

        BigDecimal paidAmount = repayments.stream()
                .filter(r -> "paid".equals(r.getStatus()))
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal unpaidAmount = repayments.stream()
                .filter(r -> "unpaid".equals(r.getStatus()))
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal overdueAmount = repayments.stream()
                .filter(r -> "overdue".equals(r.getStatus()))
                .map(r -> r.getAmount() != null ? r.getAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 剩余待还 = unpaid + overdue
        item.setRemainingAmount(unpaidAmount.add(overdueAmount));
        item.setRemainingTerm((int) (unpaidCount + overdueCount));

        // 下一期还款
        RepaymentRecordVO next = repayments.stream()
                .filter(r -> !"paid".equals(r.getStatus()))
                .min(Comparator.comparing(r -> r.getDueDate() != null ? r.getDueDate() : ""))
                .orElse(null);
        if (next != null) {
            item.setNextRepaymentDate(next.getDueDate());
            item.setNextRepaymentAmount(next.getAmount());
        } else {
            item.setNextRepaymentDate(null);
            item.setNextRepaymentAmount(BigDecimal.ZERO);
        }

        // 逾期信息
        item.setOverdueAmount(overdueAmount);
        if (overdueCount > 0) {
            // 找最早逾期的记录算天数
            Optional<RepaymentRecordVO> earliestOverdue = repayments.stream()
                    .filter(r -> "overdue".equals(r.getStatus()))
                    .min(Comparator.comparing(r -> r.getDueDate() != null ? r.getDueDate() : ""));
            if (earliestOverdue.isPresent() && earliestOverdue.get().getDueDate() != null) {
                try {
                    LocalDate dueDate = LocalDate.parse(earliestOverdue.get().getDueDate());
                    int days = (int) ChronoUnit.DAYS.between(dueDate, LocalDate.now());
                    item.setOverdueDays(Math.max(days, 0));
                } catch (Exception e) {
                    item.setOverdueDays(0);
                }
            }
        } else {
            item.setOverdueDays(0);
        }

        // 状态判定
        if (overdueCount > 0) {
            item.setStatus("overdue");
            item.setStatusDesc("逾期中");
        } else if (paidCount == totalTerms) {
            item.setStatus("cleared");
            item.setStatusDesc("已结清");
            item.setRemainingAmount(BigDecimal.ZERO);
            item.setRemainingTerm(0);
            item.setNextRepaymentDate(null);
            item.setNextRepaymentAmount(BigDecimal.ZERO);
        } else {
            item.setStatus("repaying");
            item.setStatusDesc("还款中");
        }
    }

    private LoanOverviewSummaryVO computeSummary(List<LoanOverviewItemVO> items) {
        LoanOverviewSummaryVO summary = new LoanOverviewSummaryVO();

        summary.setTotalLoanCount(items.size());

        // 累计借款 = 所有申请金额（不含已拒绝+审核中）
        BigDecimal totalBorrowed = items.stream()
                .filter(i -> !"pending".equals(i.getStatus()) && !"rejected".equals(i.getStatus()))
                .map(i -> i.getApplyAmount() != null ? i.getApplyAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.setTotalBorrowed(totalBorrowed);

        // 待还总额
        BigDecimal pending = items.stream()
                .map(i -> i.getRemainingAmount() != null ? i.getRemainingAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        summary.setPendingRepayment(pending);

        // 逾期笔数
        long overdueCount = items.stream()
                .filter(i -> "overdue".equals(i.getStatus()))
                .count();
        summary.setOverdueCount((int) overdueCount);

        // 进行中笔数（repaying + overdue）
        long activeCount = items.stream()
                .filter(i -> "repaying".equals(i.getStatus()) || "overdue".equals(i.getStatus()))
                .count();
        summary.setActiveLoanCount((int) activeCount);

        return summary;
    }
}

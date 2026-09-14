package com.loan.service.impl;

import com.loan.entity.vo.Result;
import com.loan.entity.vo.stats.*;
import com.loan.mapper.LoanIssueMapper;
import com.loan.service.DisbursementStatsService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class DisbursementStatsServiceImpl implements DisbursementStatsService {

    @Resource
    private LoanIssueMapper loanIssueMapper;

    @Override
    public Result<DisbursementStatsVO> getStats(String startDate, String endDate) {
        // 核心指标
        DisbursementSummaryVO summary = loanIssueMapper.selectSummary(startDate, endDate);

        // 趋势
        List<TrendPointVO> trend = loanIssueMapper.selectTrend(startDate, endDate);

        // 产品分布
        List<ProductDistVO> productDist = loanIssueMapper.selectProductDistribution(startDate, endDate);

        // 金额分布
        List<AmountDistVO> amountDist = loanIssueMapper.selectAmountDistribution(startDate, endDate);

        // 期限分布
        List<TermDistVO> termDist = loanIssueMapper.selectTermDistribution(startDate, endDate);

        DisbursementStatsVO stats = new DisbursementStatsVO(
                summary, trend, productDist, amountDist, termDist
        );

        return Result.success(stats);
    }
}

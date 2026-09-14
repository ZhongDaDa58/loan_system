package com.loan.mapper;

import com.loan.entity.LoanIssue;
import com.loan.entity.vo.stats.*;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface LoanIssueMapper {
    // 保存放款记录
    int insert(LoanIssue loanIssue);

    // 根据申请ID查询放款记录（避免重复放款）
    LoanIssue selectByApplicationId(@Param("applicationId") String applicationId);

    // ========== 放款统计 ==========

    /**
     * 核心指标（累计金额/笔数/今日金额）
     */
    DisbursementSummaryVO selectSummary(@Param("startDate") String startDate,
                                        @Param("endDate") String endDate);

    /**
     * 放款趋势（按月聚合）
     */
    List<TrendPointVO> selectTrend(@Param("startDate") String startDate,
                                   @Param("endDate") String endDate);

    /**
     * 产品放款占比
     */
    List<ProductDistVO> selectProductDistribution(@Param("startDate") String startDate,
                                                  @Param("endDate") String endDate);

    /**
     * 金额分布
     */
    List<AmountDistVO> selectAmountDistribution(@Param("startDate") String startDate,
                                                @Param("endDate") String endDate);

    /**
     * 期限分布
     */
    List<TermDistVO> selectTermDistribution(@Param("startDate") String startDate,
                                            @Param("endDate") String endDate);
}
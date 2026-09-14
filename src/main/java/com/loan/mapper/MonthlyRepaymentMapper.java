package com.loan.mapper;

import com.loan.entity.MonthlyRepayment;
import com.loan.entity.vo.DueItemVO;
import com.loan.entity.vo.MonthlyRepaymentVO;
import com.loan.entity.vo.stats.OverdueAmountDistVO;
import com.loan.entity.vo.stats.OverdueSummaryVO;
import com.loan.entity.vo.stats.OverdueTrendPointVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface MonthlyRepaymentMapper {
    // 批量保存每月还款明细
    int batchInsert(@Param("list") java.util.List<MonthlyRepayment> list);

    // 根据还款明细ID查询
    MonthlyRepayment selectById(@Param("repaymentId") String repaymentId);

    // 根据计划ID查询
    List<MonthlyRepaymentVO> selectByPlanId(@Param("planId") String planId);

    // 更新还款状态和还款时间
    int updateRepaymentStatus(@Param("repaymentId") String repaymentId, @Param("status") String status, @Param("repaymentTime") java.util.Date repaymentTime);

    /**
     * 根据状态查询还款明细（用于逾期检查）
     */
    List<MonthlyRepayment> selectByStatus(@Param("status") String status);
    // 查询逾期
    List<MonthlyRepayment> selectOverdueByUserId(@Param("userId") String userId);

    /**
     * 查询所有逾期按期数聚合的个数统计
     */
    java.util.List<com.loan.entity.vo.OverdueTermCountVO> selectOverdueCountGroupedByTerm();

    // ========== 用户管理模块 ==========

    /**
     * 根据用户ID查询所有还款明细（连表 repayment_plan 获取 loan_id）
     */
    List<com.loan.entity.vo.RepaymentRecordVO> selectRepaymentRecordByUserId(@Param("userId") String userId);

    // ========== 还款提醒模块 ==========

    /**
     * 查询未来 N 天内到期的未还款记录（连表 repayment_plan 取 userId）
     */
    List<com.loan.entity.vo.UpcomingRepaymentVO> selectUpcomingUnpaid(@Param("withinDays") int withinDays);

    // ========== 逾期统计 ==========

    /**
     * 逾期核心指标
     */
    OverdueSummaryVO selectOverdueSummary(@Param("startDate") String startDate,
                                           @Param("endDate") String endDate);

    /**
     * 月度逾期率趋势
     */
    List<OverdueTrendPointVO> selectOverdueTrend(@Param("startDate") String startDate,
                                                  @Param("endDate") String endDate);

    /**
     * 逾期金额分布
     */
    List<OverdueAmountDistVO> selectOverdueAmountDistribution(@Param("startDate") String startDate,
                                                               @Param("endDate") String endDate);

    /**
     * 统计期内总应还款笔数（用于计算逾期率）
     */
    Long countTotalDueWithinPeriod(@Param("startDate") String startDate,
                                    @Param("endDate") String endDate);

    /**
     * 每月逾期笔数（趋势用）
     */
    List<com.loan.service.impl.OverdueStatsServiceImpl.OverdueTrendRaw> selectOverdueCountByMonth(
            @Param("startDate") String startDate, @Param("endDate") String endDate);

    /**
     * 每月总应还款笔数（趋势用）
     */
    List<com.loan.service.impl.OverdueStatsServiceImpl.TotalDueRaw> selectTotalDueCountByMonth(
            @Param("startDate") String startDate, @Param("endDate") String endDate);

    // ========== 立即还款 ==========

    /**
     * 查询用户待还款项（unpaid + overdue，含产品名称），逾期项在前
     */
    List<DueItemVO> selectDueItemsByUserId(@Param("userId") String userId);
}
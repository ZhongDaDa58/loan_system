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

    /**
     * 逾期检查的候选集：{@code unpaid} + {@code overdue} 且已过到期日。
     *
     * <p><b>为什么不是 {@link #selectByStatus}('unpaid')</b>：
     * 原来的实现只扫 {@code unpaid}，一旦标记成 {@code overdue} 就<b>再也扫不到</b> ——
     * 逾期天数从来没被记录过，也就无法做 M1/M2/M3 分级、无法计罚息。
     * 这是整个逾期体系的地基断点。
     *
     * <p>返回天数字段 {@code overdue_days} / {@code overdue_level}，供调用方
     * 判断"是否需要升级"（只在跃迁时发通知，避免每日骚扰）。
     */
    List<MonthlyRepayment> selectOverdueCandidates();

    /**
     * 回写逾期跟踪字段（天数 / 等级 / 等级跃迁时间）。
     *
     * <p>⚠️ 不含罚息 —— 罚息计算在后续步骤单独做（需要幂等，靠
     * {@code penalty_last_calc_date} 保证每天只计一次）。
     *
     * @param levelChanged 等级是否发生跃迁；只有为 true 时才更新 {@code overdue_level_changed_at}
     */
    int updateOverdueTracking(@Param("repaymentId") String repaymentId,
                              @Param("overdueDays") Integer overdueDays,
                              @Param("overdueLevel") String overdueLevel,
                              @Param("levelChanged") boolean levelChanged);

    /**
     * 记录"已扣到哪个等级"—— 逾期扣分的幂等标记。
     *
     * <p>同一期同一等级只能扣一次；靠这个字段判断"要不要扣"，
     * 否则每天跑任务都会重复扣分。
     */
    int updateScoreDeductedLevel(@Param("repaymentId") String repaymentId,
                                @Param("level") String level);

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
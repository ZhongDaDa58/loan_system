package com.loan.mapper;

import com.loan.entity.MonthlyRepayment;
import com.loan.entity.vo.MonthlyRepaymentVO;
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

}
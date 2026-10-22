package com.loan.mapper;
import com.loan.entity.LoanApplication;
import com.loan.entity.vo.LoanApplicationVO;
import com.loan.entity.vo.LoanOverviewItemVO;
import com.loan.entity.vo.loanDetail.LoanHistoryRecordVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.Date;
import java.util.List;
import com.loan.entity.vo.DateMetricVO;

public interface LoanApplicationMapper {
    // 保存申请记录
    int insert(LoanApplication loanApplication);

    // 根据用户ID查询申请记录
    List<LoanApplicationVO> selectByUserId(@Param("userId") String userId);

    // 根据申请ID查询（审核时校验）
    LoanApplication selectByApplicationId(@Param("applicationId") String applicationId);

    // 更新申请状态
    int updateStatus(@Param("applicationId") String applicationId, @Param("status") String status);

    /**
     * 条件更新状态（乐观锁）：只有当前状态仍等于 {@code expectedStatus} 时才更新。
     *
     * <p><b>为什么需要它</b>：{@link #updateStatus} 只按 applicationId 更新，
     * 是"先读后写"模式 —— 两个审核员同时提交时，会出现：
     * <pre>
     * 线程A: 读 status=pending  线程B: 读 status=pending    ← 都通过校验
     * 线程A: UPDATE → approved  线程B: UPDATE → rejected    ← B 覆盖了 A
     * </pre>
     * 结果：申请被审两次、`audit_record` 两条、结论以最后写入的为准。
     *
     * <p>用条件更新把"检查 + 更新"变成<b>一条原子 SQL</b>，
     * 靠影响行数判断谁抢到了：返回 0 = 已被他人改变状态。
     *
     * @return 影响的期望行数为 1；<b>返回 0 表示状态已被他人改变</b>
     */
    int updateStatusIfCurrent(@Param("applicationId") String applicationId,
                              @Param("expectedStatus") String expectedStatus,
                              @Param("status") String status);

    // 根据申请ID查询申请金额
    BigDecimal selectApplyAmountByApplicationId(@Param("applicationId") String applicationId);

    //查询所有已通过申请
    List<LoanApplicationVO> selectAllPassed();

    // 统计指定状态的申请数量
    int countByStatus(@Param("status") String status);

    // 统计某日被审核（update_time）过的申请数量（不包括仍为 pending 的）
    int countAuditedByDate(@Param("date") String date);

    // 统计某日审核通过（approved）的申请数量
    int countApprovedByDate(@Param("date") String date);

    // 按日期范围统计已审核数量（按 update_time 的日期分组），返回每一天的日期与数量
    List<DateMetricVO> selectAuditedCountGroupByDate(@Param("startDate") String startDate, @Param("endDate") String endDate);

    // 按日期范围统计已通过数量（按 update_time 的日期分组），返回每一天的日期与数量
    List<DateMetricVO> selectApprovedCountGroupByDate(@Param("startDate") String startDate, @Param("endDate") String endDate);

    void updateContractInfo(@Param("applicationId") String applicationId,
                            @Param("path") String path,
                            @Param("hash") String hash);
    /**
     * 更新合同状态
     */
    void updateContractStatus(@Param("applicationId") String applicationId,
                              @Param("status") Integer status);

    /**
     * 仅更新最终哈希值（盖章后使用）
     */
    void updateContractHash(@Param("applicationId") String applicationId,
                            @Param("hash") String hash);

    /**
     * 更新用户签署时间
     */
    void updateSignTime(@Param("applicationId") String applicationId, @Param("signTime") java.util.Date signTime);

    /**
     * 更新平台盖章时间
     */
    void updateStampTime(@Param("applicationId") String applicationId, @Param("stampTime") java.util.Date stampTime);

    // ========== 历史借贷模块 ==========

    /**
     * 查询某用户在当前申请时间之前的全部历史贷款记录（含放款时间）
     */
    List<LoanHistoryRecordVO> selectLoanHistoryByUser(@Param("userId") String userId,
                                                       @Param("beforeTime") Date beforeTime,
                                                       @Param("excludeAppId") String excludeAppId);

    // ========== 贷款概况 ==========

    /**
     * 查询用户贷款概况列表（含产品名称、利率、计划ID）
     */
    List<LoanOverviewItemVO> selectLoanOverview(@Param("userId") String userId);
}
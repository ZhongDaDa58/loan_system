package com.loan.mapper;
import com.loan.entity.LoanApplication;
import com.loan.entity.vo.LoanApplicationVO;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

public interface LoanApplicationMapper {
    // 保存申请记录
    int insert(LoanApplication loanApplication);

    // 根据用户ID查询申请记录
    List<LoanApplicationVO> selectByUserId(@Param("userId") String userId);

    // 根据申请ID查询（审核时校验）
    LoanApplication selectByApplicationId(@Param("applicationId") String applicationId);

    // 更新申请状态
    int updateStatus(@Param("applicationId") String applicationId, @Param("status") String status);

    // 根据申请ID查询申请金额
    BigDecimal selectApplyAmountByApplicationId(@Param("applicationId") String applicationId);

    //查询所有已通过申请
    List<LoanApplicationVO> selectAllPassed();

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

}
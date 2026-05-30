package com.loan.mapper;
import com.loan.entity.RepaymentPlan;
import com.loan.entity.vo.RepaymentPlanListVO;
import com.loan.entity.vo.RepaymentPlanVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RepaymentPlanMapper {
    // 保存还款计划
    int insert(RepaymentPlan repaymentPlan);

    // 根据用户ID查询还款计划列表
    List<RepaymentPlanVO> selectByUserId(@Param("userId") String userId);

    // 根据申请ID查询还款计划列表
    RepaymentPlanVO selectByApplicationId(@Param("applicationId") String applicationId);

    // 查询所有用户的还款计划列表（供管理端使用，含用户信息和逾期状态）

    List<RepaymentPlanListVO> selectAllPlansWithUserInfo();

}
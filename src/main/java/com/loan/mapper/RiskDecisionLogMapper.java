package com.loan.mapper;

import com.loan.entity.RiskDecisionLog;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface RiskDecisionLogMapper {

    int insert(RiskDecisionLog log);

    RiskDecisionLog selectByApplicationId(@Param("applicationId") String applicationId);

    List<RiskDecisionLog> selectByUserId(@Param("userId") String userId);

    List<RiskDecisionLog> selectRecentLogs(@Param("limit") int limit);
}

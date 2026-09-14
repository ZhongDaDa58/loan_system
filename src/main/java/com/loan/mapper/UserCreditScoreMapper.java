package com.loan.mapper;

import com.loan.entity.UserCreditScore;
import com.loan.entity.vo.CreditHistoryPointVO;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserCreditScoreMapper {

    // 根据用户ID查询信用分
    UserCreditScore selectByUserId(@Param("userId") String userId);

    // 插入信用分记录
    int insert(UserCreditScore userCreditScore);

    // 更新信用分
    int updateCreditScore(@Param("userId") String userId, @Param("creditScore") Integer creditScore);

    // 如果不存在则插入，存在则更新
    int upsert(UserCreditScore userCreditScore);

    // ========== 用户管理模块 ==========

    /**
     * 查询用户信用分历史（按时间正序）
     */
    List<CreditHistoryPointVO> selectHistoryByUserId(@Param("userId") String userId);
}

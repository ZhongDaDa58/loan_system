package com.loan.mapper;

import com.loan.entity.UserCreditScore;
import org.apache.ibatis.annotations.Param;

public interface UserCreditScoreMapper {

    // 根据用户ID查询信用分
    UserCreditScore selectByUserId(@Param("userId") String userId);

    // 插入信用分记录
    int insert(UserCreditScore userCreditScore);

    // 更新信用分
    int updateCreditScore(@Param("userId") String userId, @Param("creditScore") Integer creditScore);

    // 如果不存在则插入，存在则更新
    int upsert(UserCreditScore userCreditScore);
}

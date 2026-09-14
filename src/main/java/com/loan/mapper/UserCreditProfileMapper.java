package com.loan.mapper;

import com.loan.entity.UserCreditProfile;
import org.apache.ibatis.annotations.Param;

public interface UserCreditProfileMapper {

    int insert(UserCreditProfile profile);

    int update(UserCreditProfile profile);

    UserCreditProfile selectByUserId(@Param("userId") String userId);

    int deleteByUserId(@Param("userId") String userId);

    /**
     * 更新评分结果（单独更新避免全量覆盖）
     */
    int updateScore(@Param("userId") String userId, @Param("creditScore") Integer creditScore);
}

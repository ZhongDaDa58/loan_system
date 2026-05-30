package com.loan.mapper;

import com.loan.entity.UserBankCard;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserBankCardMapper {
    int insert(UserBankCard userBankCard);
    List<UserBankCard> selectByUserId(@Param("userId") String userId);
    int updateDefaultCard(@Param("userId") String userId, @Param("cardId") Long cardId);
    int deleteById(@Param("id") Long id);
    boolean isBound(@Param("userId") String userId, @Param("poolCardId") Integer poolCardId);
    UserBankCard selectById(@Param("id") Long id);
}

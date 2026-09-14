package com.loan.mapper;

import com.loan.entity.UserBankCard;
import com.loan.entity.vo.UserBankCardVO;
import org.apache.ibatis.annotations.Param;
import java.util.List;

public interface UserBankCardMapper {
    int insert(UserBankCard userBankCard);
    List<UserBankCard> selectByUserId(@Param("userId") String userId);
    int updateDefaultCard(@Param("userId") String userId, @Param("cardId") Long cardId);
    int deleteById(@Param("id") Long id);
    boolean isBound(@Param("userId") String userId, @Param("poolCardId") Integer poolCardId);
    UserBankCard selectById(@Param("id") Long id);

    // ========== 用户管理模块 ==========

    /**
     * 查询用户银行卡列表（含银行名称，连表 mock_bank_card_pool）
     */
    List<UserBankCardVO> selectUserBankCardVOByUserId(@Param("userId") String userId);
}

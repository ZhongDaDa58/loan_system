package com.loan.service;

import com.loan.entity.MockBankCardPool;
import com.loan.entity.UserBankCard;
import com.loan.entity.dto.BindCardDTO;

import java.math.BigDecimal;
import java.util.List;

public interface MockBankCardService {
    // 验证卡号是否在模拟池中
    MockBankCardPool validateCardInPool(String cardNumber);

    // 绑定银行卡
    String bindCard(String userId, BindCardDTO dto);

    // 获取用户已绑定的卡片列表
    List<UserBankCard> getUserCards(String userId);

    // 设置默认卡片
    void setDefaultCard(String userId, Long cardId);

    // 解绑卡片
    void unbindCard(String userId, Long cardId);

    // 获取卡片详情（用于交易前检查）
    MockBankCardPool getCardById(Integer poolCardId);

    void updateBalance(Integer poolCardId, BigDecimal amount, String direction);
    void checkBalance(Integer poolCardId, BigDecimal amount);

}

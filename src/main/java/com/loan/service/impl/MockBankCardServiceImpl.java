package com.loan.service.impl;

import com.loan.entity.MockBankCardPool;
import com.loan.entity.UserBankCard;
import com.loan.entity.UserIdentity;
import com.loan.entity.dto.BindCardDTO;
import com.loan.exception.BusinessException;
import com.loan.mapper.MockBankCardPoolMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.MockBankCardService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MockBankCardServiceImpl implements MockBankCardService {

    @Resource
    private MockBankCardPoolMapper poolMapper;
    @Resource
    private UserBankCardMapper userCardMapper;
    @Resource
    private UserIdentityMapper userIdentityMapper;
    @Override
    public MockBankCardPool validateCardInPool(String cardNumber) {
        MockBankCardPool card = poolMapper.selectByCardNumber(cardNumber);
        if (card == null) {
            throw new BusinessException(404, "卡号不存在，请输入系统提供的模拟卡号");
        }
        if (!"ACTIVE".equals(card.getStatus())) {
            throw new BusinessException(400, "该卡状态异常：" + card.getStatus());
        }
        return card;
    }

    @Override
    @Transactional
    public String bindCard(String userId, BindCardDTO dto) {
        UserIdentity identity = userIdentityMapper.selectByUserId(userId);
        if (identity == null || identity.getVerifyStatus() != 1) {
            throw new BusinessException(403, "请先完成实名认证后再绑卡");
        }
        // 1. 验证卡号是否在模拟池中
        MockBankCardPool poolCard = validateCardInPool(dto.getCardNumber());
        if (poolCard == null) {
            throw new BusinessException(404, "不支持该银行卡");
        }
        // ⭐ 2. 四要素一致性校验 (模拟银行网关)
        // 检查用户输入的姓名、身份证、手机号是否与卡池里预设的一致
        if (!poolCard.getCardholderName().equals(identity.getUserRealName())) {
            throw new BusinessException(400, "银行卡持卡人姓名与实名认证不符");
        }
        if (!poolCard.getIdCard().equals(identity.getIdCardNumber())) {
            throw new BusinessException(400, "银行卡预留身份证与实名认证不符");
        }
        if (!poolCard.getPhone().equals(dto.getPhone())) {
            throw new BusinessException(400, "预留手机号不正确");
        }

        // 3. 检查是否已绑定
        if (userCardMapper.isBound(userId, poolCard.getId())) {
            throw new BusinessException(400, "该卡已被您绑定");
        }

        // 4. 执行绑卡
        UserBankCard userCard = new UserBankCard();
        userCard.setUserId(userId);
        userCard.setPoolCardId(poolCard.getId());
        userCard.setCardNumber(dto.getCardNumber());
        userCard.setAliasName(poolCard.getBankName() + "(" + dto.getCardNumber().substring(dto.getCardNumber().length() - 4) + ")");
        userCard.setIsDefault(false);
        userCard.setBindStatus("ACTIVE");

        userCardMapper.insert(userCard);
        return userCard.getId().toString();
    }

    @Override
    public List<UserBankCard> getUserCards(String userId) {
        return userCardMapper.selectByUserId(userId);
    }

    @Override
    @Transactional
    public void setDefaultCard(String userId, Long cardId) {
        UserBankCard card = userCardMapper.selectById(cardId);
        if (card == null || !card.getUserId().equals(userId)) {
            throw new BusinessException(400, "卡片不存在或无权操作");
        }
        userCardMapper.updateDefaultCard(userId, cardId);
    }

    @Override
    @Transactional
    public void unbindCard(String userId, Long cardId) {
        UserBankCard card = userCardMapper.selectById(cardId);
        if (card == null || !card.getUserId().equals(userId)) {
            throw new BusinessException(400, "卡片不存在或无权操作");
        }
        userCardMapper.deleteById(cardId);
    }

    @Override
    public MockBankCardPool getCardById(Integer poolCardId) {
        return poolMapper.selectById(poolCardId);
    }

    @Override
    @Transactional
    public void updateBalance(Integer poolCardId, BigDecimal amount, String direction) {
        int rows;
        if ("OUT".equals(direction)) {
            rows = poolMapper.decreaseBalance(poolCardId, amount);
        } else {
            rows = poolMapper.increaseBalance(poolCardId, amount);
        }
        if (rows == 0) {
            throw new BusinessException(500, "余额更新失败，请检查卡片状态或余额是否充足");
        }
    }

    @Override
    public void checkBalance(Integer poolCardId, BigDecimal amount) {
        MockBankCardPool card = poolMapper.selectById(poolCardId);
        if (card == null) {
            throw new BusinessException(404, "卡片不存在");
        }
        if (card.getBalance().compareTo(amount) < 0) {
            throw new BusinessException(400, String.format("模拟卡余额不足！当前余额: %.2f, 需要: %.2f",
                    card.getBalance(), amount));
        }
    }
}

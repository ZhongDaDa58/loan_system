package com.loan.service.impl;

import com.loan.entity.MockBankCardPool;
import com.loan.entity.PaymentTransaction;
import com.loan.entity.UserBankCard;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.MonthlyRepaymentMapper;
import com.loan.mapper.PaymentTransactionMapper;
import com.loan.mapper.UserBankCardMapper;
import com.loan.service.MockBankCardService;
import com.loan.service.PaymentService;
import com.loan.util.IdUtil;
import jakarta.annotation.Resource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Date;
import java.util.Random;

@Service
public class PaymentServiceImpl implements PaymentService {

    @Resource
    private MockBankCardService cardService;

    @Resource
    private UserBankCardMapper userCardMapper;

    @Resource
    private PaymentTransactionMapper transactionMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private MonthlyRepaymentMapper repaymentMapper;

    private final Random random = new Random();

    @Override
    @Transactional
    public Result<?> initiateDisbursement(String applicationId, String userId, Long cardId, BigDecimal amount) {
        // 1. 验证用户是否拥有该卡
        UserBankCard userCard = userCardMapper.selectById(cardId);
        if (userCard == null || !userCard.getUserId().equals(userId)) {
            return Result.error(400, "无效银行卡或无权操作");
        }

        String txnId = IdUtil.generateId();
        createTransaction(txnId, applicationId, userId, userCard.getPoolCardId(), amount, "LOAN_DISBURSEMENT", "IN");

        // 2. 异步处理放款（模拟银行到账延迟）
        processDisbursementAsync(txnId, applicationId, userCard.getPoolCardId(), amount);

        return Result.success("放款申请已提交，资金将转入尾号" + userCard.getCardNumber().substring(userCard.getCardNumber().length() - 4));
    }

    @Async
    public void processDisbursementAsync(String txnId, String applicationId, Integer poolCardId, BigDecimal amount) {
        try {
            Thread.sleep(random.nextInt(2000) + 1000); // 模拟1-3秒延迟

            MockBankCardPool card = getCardAndCheckStatus(poolCardId);
            BigDecimal before = card.getBalance();

            // 执行入账
            cardService.updateBalance(poolCardId, amount, "IN");

            BigDecimal after = cardService.getCardById(poolCardId).getBalance();

            // 更新交易状态
            transactionMapper.updateStatus(txnId, "SUCCESS", null, null, before, after);

            // 更新贷款申请状态为“已放款”
            loanApplicationMapper.updateStatus(applicationId, "ISSUED");

        } catch (Exception e) {
            transactionMapper.updateStatus(txnId, "FAILED", "DISBURSE_ERROR", e.getMessage(), null, null);
        }
    }

    @Override
    @Transactional
    public Result<?> initiateRepayment(String orderNo, String userId, Long cardId, BigDecimal amount) {
        // 1. 验证用户是否拥有该卡
        UserBankCard userCard = userCardMapper.selectById(cardId);
        if (userCard == null || !userCard.getUserId().equals(userId)) {
            return Result.error(400, "无效银行卡或无权操作");
        }

        String txnId = IdUtil.generateId();
        createTransaction(txnId, orderNo, userId, userCard.getPoolCardId(), amount, "REPAYMENT", "OUT");

        try {
            // 2. 同步处理还款
            processRepaymentSync(txnId, orderNo, userCard.getPoolCardId(), amount);
            return Result.success("还款成功");
        } catch (Exception e) {
            return Result.error(500, "还款失败：" + e.getMessage());
        }
    }

    private void processRepaymentSync(String txnId, String orderNo, Integer poolCardId, BigDecimal amount) {
        // 检查余额
        cardService.checkBalance(poolCardId, amount);

        MockBankCardPool card = getCardAndCheckStatus(poolCardId);
        BigDecimal before = card.getBalance();

        // 执行扣款
        cardService.updateBalance(poolCardId, amount, "OUT");
        BigDecimal after = cardService.getCardById(poolCardId).getBalance();

        // 更新交易状态
        transactionMapper.updateStatus(txnId, "SUCCESS", null, null, before, after);

        // 更新业务状态（假设 RepaymentMapper 里有这个方法，如果没有可以暂时注释掉）
        // repaymentMapper.updateStatusByOrderNo(orderNo, "PAID");
    }

    @Override
    public Result<?> getTransactionHistory(String userId, int page, int size) {
        // 简单实现，后续可以补充 Mapper 的分页查询
        return Result.success("查询成功");
    }

    private MockBankCardPool getCardAndCheckStatus(Integer poolCardId) {
        MockBankCardPool card = cardService.getCardById(poolCardId);
        if (card == null || !"ACTIVE".equals(card.getStatus())) {
            throw new BusinessException(400, "卡片状态异常，无法交易");
        }
        return card;
    }

    private void createTransaction(String txnId, String orderNo, String userId, Integer poolCardId, BigDecimal amount, String type, String direction) {
        PaymentTransaction txn = new PaymentTransaction();
        txn.setTransactionId(txnId);
        txn.setOrderNo(orderNo);
        txn.setUserId(userId);
        txn.setPoolCardId(poolCardId);
        txn.setAmount(amount);
        txn.setType(type);
        txn.setDirection(direction);
        txn.setStatus("PENDING");
        txn.setCreateTime(new Date());
        transactionMapper.insert(txn);
    }
}

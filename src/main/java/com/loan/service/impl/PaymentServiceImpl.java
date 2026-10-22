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

    /** 2.3：放款异步处理。必须经代理调用，@Async 才生效（原同类内部调用是无效的） */
    @Resource
    private DisbursementAsyncService disbursementAsyncService;

    /** 阶段四 4.3：幂等控制（防重复放款 / 重复还款） */
    @Resource
    private com.loan.service.IdempotencyService idempotencyService;

    private final Random random = new Random();

    @Override
    @Transactional
    public Result<?> initiateDisbursement(String applicationId, String userId, Long cardId, BigDecimal amount) {
        // 阶段四 4.3：幂等检查 —— ⚠️ 放款是资金类操作，重复执行 = 直接资金损失
        // 同一 Idempotency-Key 在 5 分钟内重复提交 → 409
        idempotencyService.acquire("disbursement", userId);
        // 1. 验证用户是否拥有该卡
        UserBankCard userCard = userCardMapper.selectById(cardId);
        if (userCard == null || !userCard.getUserId().equals(userId)) {
            return Result.error(400, "无效银行卡或无权操作");
        }

        String txnId = IdUtil.generateId();
        createTransaction(txnId, applicationId, userId, userCard.getPoolCardId(), amount, "LOAN_DISBURSEMENT", "IN");

        // 2. 异步处理放款（模拟银行到账延迟）
        // 2.3 修正：原为同类内部直接调用 this.processDisbursementAsync(...)，
        // 不经过 Spring AOP 代理，@Async 从未生效 → 放款接口每次都真 sleep 1~3 秒。
        disbursementAsyncService.processDisbursementAsync(txnId, applicationId, userCard.getPoolCardId(), amount);

        return Result.success("放款申请已提交，资金将转入尾号" + userCard.getCardNumber().substring(userCard.getCardNumber().length() - 4));
    }

    @Override
    @Transactional
    public Result<?> initiateRepayment(String orderNo, String userId, Long cardId, BigDecimal amount) {
        // 阶段四 4.3：幂等检查 —— 重复还款会导致重复扣款
        idempotencyService.acquire("repayment", userId);
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

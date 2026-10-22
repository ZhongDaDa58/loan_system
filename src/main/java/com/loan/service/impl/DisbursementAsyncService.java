package com.loan.service.impl;

import com.loan.config.AsyncConfig;
import com.loan.entity.MockBankCardPool;
import com.loan.exception.BusinessException;
import com.loan.mapper.LoanApplicationMapper;
import com.loan.mapper.PaymentTransactionMapper;
import com.loan.service.MockBankCardService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Random;

/**
 * 放款异步处理（阶段二 2.3）
 *
 * <p><b>为什么单独抽一个 Bean</b>：
 * 原实现里 {@code @Async} 方法写在 {@code PaymentServiceImpl} 内部，并在<b>同一个类</b>中
 * 通过 {@code this.processDisbursementAsync(...)} 调用。
 * <b>Spring 的 {@code @Async} 依赖 AOP 代理，同类内部调用不经过代理</b> ——
 * 所以那个 {@code @Async} 从来没生效过，放款接口每次都会真的 {@code sleep} 1~3 秒。
 *
 * <p>抽成独立 Bean 后，{@code PaymentServiceImpl} 通过注入的代理调用，{@code @Async} 才会生效。
 *
 * <p><b>注意</b>：本方法<b>不加 {@code @Transactional}</b> ——
 * 异步线程与调用方不在同一事务里，加注解只会造成"以为有事务"的误解。
 * 失败时通过 {@code transactionMapper.updateStatus(FAILED)} 记录状态。
 */
@Service
public class DisbursementAsyncService {

    private static final Logger log = LoggerFactory.getLogger(DisbursementAsyncService.class);

    @Resource
    private MockBankCardService cardService;

    @Resource
    private PaymentTransactionMapper transactionMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    private final Random random = new Random();

    /**
     * 异步处理放款入账。
     *
     * <p>显式指定 {@code appTaskExecutor}：不指定时用 {@code AsyncConfigurer#getAsyncExecutor()}，
     * 但显式写出可避免将来有人改动默认执行器时这里跟着变。
     */
    @Async(AsyncConfig.EXECUTOR_NAME)
    public void processDisbursementAsync(String txnId, String applicationId,
                                         Integer poolCardId, BigDecimal amount) {
        log.info("💸 开始异步放款 txnId={}, applicationId={}, amount={}", txnId, applicationId, amount);
        try {
            Thread.sleep(random.nextInt(2000) + 1000); // 模拟 1-3 秒银行到账延迟

            MockBankCardPool card = getCardAndCheckStatus(poolCardId);
            BigDecimal before = card.getBalance();

            // 执行入账
            cardService.updateBalance(poolCardId, amount, "IN");

            BigDecimal after = cardService.getCardById(poolCardId).getBalance();

            // 更新交易状态
            transactionMapper.updateStatus(txnId, "SUCCESS", null, null, before, after);

            // 更新贷款申请状态为"已放款"
            // 阶段四 4.3 补：条件更新 —— 只有当前仍是 approved 才更新。
            // 异步任务里**不抛异常**：此时钱已经加到卡上了（上一行 updateBalance 已提交），
            // 抛出去只会把流水标成 FAILED，反而误导「没放款」。正确做法是记 error 告警，
            // 由人工核对（钱出去了但状态没更新 = 需要介入的数据不一致）。
            int statusRows = loanApplicationMapper.updateStatusIfCurrent(
                    applicationId,
                    com.loan.entity.enums.ApplicationStatusEnum.APPROVED.getCode(),
                    "ISSUED");
            if (statusRows != 1) {
                log.error("🔴 放款成功但申请状态更新失败（可能已被并发放款）: "
                                + "txnId={}, applicationId={}, 金额={} —— 需人工核对账目",
                        txnId, applicationId, amount);
            }

            log.info("✅ 异步放款完成 txnId={}, applicationId={}", txnId, applicationId);

        } catch (Exception e) {
            // 保留堆栈：放款失败是资金相关的高危事件，必须能事后追溯
            log.error("❌ 异步放款失败 txnId={}, applicationId={}", txnId, applicationId, e);
            transactionMapper.updateStatus(txnId, "FAILED", "DISBURSE_ERROR", e.getMessage(), null, null);
        }
    }

    private MockBankCardPool getCardAndCheckStatus(Integer poolCardId) {
        MockBankCardPool card = cardService.getCardById(poolCardId);
        if (card == null || !"ACTIVE".equals(card.getStatus())) {
            throw new BusinessException(400, "卡片状态异常，无法交易");
        }
        return card;
    }
}

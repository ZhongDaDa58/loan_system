package com.loan.service.impl;

import com.loan.service.ContractService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 合同草稿生成（静默失败版）
 *
 * <p><b>为什么单独成类</b>：{@code submitApply} 和 {@code submitApplyWithBasicInfo}
 * 两条链路都要"自动通过后顺手生成合同草稿"，此前是主类里的一个私有方法，
 * 两条链路共用同一个私有方法 —— 抽类后两条链路可以各自注入，不必把主类拆成两半还留着共享方法。
 *
 * <p><b>为什么失败不抛异常</b>：合同生成失败**不影响申请结果** ——
 * 用户进入签约页时，{@code LoanApplicationQueryService#queryApplyProgress}
 * 会检测到"已通过但无合同"并兜底补生成。所以这里是"尽力而为"的预生成，
 * 只为让用户少等一下，失败了静默记日志即可。
 */
@Service
public class ContractDraftService {

    private static final Logger log = LoggerFactory.getLogger(ContractDraftService.class);

    @Resource
    private ContractService contractService;

    /**
     * 生成合同草稿；任何异常只记日志，不向上抛。
     *
     * @param applicationId 申请 ID
     */
    public void generateQuietly(String applicationId) {
        try {
            contractService.generateDraftContract(applicationId);
            log.info("📄 合同草稿已生成: {}", applicationId);
        } catch (Exception e) {
            log.warn("⚠️ 合同草稿生成失败（不影响申请，签约时会重试）: {} - {}", applicationId, e.getMessage());
        }
    }
}

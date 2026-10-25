package com.loan.controller;

import com.loan.entity.vo.Result;
import com.loan.service.DataSourceService;
import com.loan.service.LoanApplicationService;
import com.loan.service.LoanProductService;
import com.loan.service.RepaymentService;
import io.swagger.v3.oas.annotations.Hidden;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 供 Python AI 服务调用的**只读**内部接口。
 *
 * <p><b>准入控制</b>：{@link com.loan.config.CsInternalAuthInterceptor} 校验
 * {@code X-CS-Service-Token} 并把 {@code X-CS-User-Id} 写入 request attribute。
 * 所以这里的写法与业务 Controller 完全一致（{@code request.getAttribute("userId")}），
 * 也就天然不可能被模型指定成别人。
 *
 * <p><b>只读 + 最小字段</b>：不提供任何写操作；返回的都是用户自己在 App 上能看到的数据。
 *
 * <p>{@code @Hidden} 让这些接口不出现在 Swagger 上（Springdoc 会扫 com.loan.controller）。
 */
@Hidden
@RestController
@RequestMapping("/internal/cs")
public class CsInternalController {

    @Resource
    private RepaymentService repaymentService;

    @Resource
    private LoanApplicationService loanApplicationService;

    @Resource
    private LoanProductService loanProductService;

    @Resource
    private DataSourceService dataSourceService;

    /** 待还款项（含逾期天数、逾期总额）。注意 overduePenalty 恒为 0 —— 后端无罚息数据列。 */
    @GetMapping("/repayment/due-items")
    public Result<?> dueItems(HttpServletRequest request) {
        return repaymentService.queryDueItems(userId(request));
    }

    /** 申请进度（含状态、状态描述、时间、合同状态）。注意不含拒绝原因字段。 */
    @GetMapping("/application/progress")
    public Result<?> applicationProgress(HttpServletRequest request) {
        return loanApplicationService.queryApplyProgress(userId(request));
    }

    /** 在售产品列表（额度范围、利率区间、期数）。 */
    @GetMapping("/products")
    public Result<?> products() {
        return Result.success(loanProductService.getProductList());
    }

    /** 已授权的数据源清单（用于"为什么采集我的信息 / 怎么撤回"）。 */
    @GetMapping("/data-source/authorizations")
    public Result<?> authorizations(HttpServletRequest request) {
        return dataSourceService.listAuthorizations(userId(request));
    }

    private String userId(HttpServletRequest request) {
        return (String) request.getAttribute("userId");
    }
}

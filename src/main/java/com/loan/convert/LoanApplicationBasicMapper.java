package com.loan.convert;

import com.loan.entity.LoanApplication;
import com.loan.entity.vo.loanDetail.LoanApplicationBasicVO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 申请实体 → 审批端基本信息 VO（阶段四 4.5 MapStruct）
 *
 * <p><b>替换了什么</b>：{@code LoanApplicationQueryService#getApplicationBasic} 里
 * 13 行手写 {@code setXxx}。
 *
 * <p><b>⚠️ 为什么放在 {@code com.loan.convert} 而不是 {@code com.loan.mapper.convert}</b>：
 * 项目的 {@code MyBatisConfig} 上有 {@code @MapperScan("com.loan.mapper")} ——
 * MyBatis 会把该包下**所有接口**当成 XML Mapper 代理。
 * MapStruct 的 {@code @Mapper} 和 MyBatis 的 {@code @Mapper} <b>是不同注解但同名</b>，
 * 放进去会导致 Spring 注入 MyBatis 代理而不是 MapStruct 生成的 {@code @Component}，
 * 运行时报 {@code Invalid bound statement (not found)}。
 *
 * <p><b>哪些字段不在 Mapper 里</b>（需要业务计算，由调用方补）：
 * <ul>
 *   <li>{@code productName} / {@code interestRate} / {@code repaymentMethod} —— 来自产品配置</li>
 *   <li>{@code monthlyPayment} —— 等额本息计算</li>
 *   <li>{@code cardBankName} / {@code cardNumber} —— 来自银行卡（且要脱敏）</li>
 * </ul>
 * <b>用 {@code ignore = true} 显式声明</b>，而不是让 MapStruct 静默忽略 ——
 * 显式声明后，如果 VO 将来新增字段，编译会提示"未映射"，不会悄悄漏掉。
 */
@Mapper(componentModel = "spring")
public interface LoanApplicationBasicMapper {

    @Mapping(target = "productName", ignore = true)
    @Mapping(target = "interestRate", ignore = true)
    @Mapping(target = "repaymentMethod", ignore = true)
    @Mapping(target = "monthlyPayment", ignore = true)
    @Mapping(target = "cardBankName", ignore = true)
    @Mapping(target = "cardNumber", ignore = true)
    LoanApplicationBasicVO toVo(LoanApplication application);
}

package com.loan.convert;

import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.UserProfileDTO;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

/**
 * 信用档案 → 评分卡入参（阶段四 4.5 MapStruct）
 *
 * <p><b>替换了什么</b>：原来是 {@code ScorecardDetailAssembler#buildUserProfileFromProfile}
 * 里的 6 行手写 setter。
 *
 * <p><b>为什么用 MapStruct 而不是手写</b>：
 * <ul>
 *   <li><b>编译期生成</b>，运行时零反射开销（对比 BeanUtils.copyProperties）</li>
 *   <li><b>字段名对不上时编译报错</b> —— 手写 setter 如果拼错方法名根本编译不过，
 *       但如果<b>漏写一个字段</b>，编译是过的，运行时就是 null，很难发现</li>
 *   <li>默认值用注解表达，比三目运算符清晰</li>
 * </ul>
 *
 * <p><b>⚠️ 默认值语义必须与手写代码一致</b>：
 * 评分卡对每个特征都有固定 WOE 分箱，缺值时无法落箱。原代码用"业务上最保守的中性值"填充：
 * <ul>
 *   <li>年龄 18 —— 最低可申请年龄</li>
 *   <li>利用率 / 负债率 0 —— 无负担</li>
 *   <li>账户数 / 家属数 0</li>
 * </ul>
 * <b>注意 {@code monthlyIncome} 没有默认值</b>（原代码也是直接赋值）—— 收入缺失就该是 null，
 * 由规则层判定"数据不足"转人工，不能假定一个假收入。
 */
@Mapper(componentModel = "spring")
public interface UserProfileMapper {

    @Mapping(target = "revolvingUtil", defaultExpression = "java(java.math.BigDecimal.ZERO)")
    @Mapping(target = "age", defaultValue = "18")
    @Mapping(target = "debtRatio", defaultExpression = "java(java.math.BigDecimal.ZERO)")
    @Mapping(target = "creditLines", defaultValue = "0")
    @Mapping(target = "dependents", defaultValue = "0")
    UserProfileDTO toDto(UserCreditProfile profile);
}

package com.loan.service.impl;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.loan.entity.MockFinancialProfile;
import com.loan.entity.SysUser;
import com.loan.entity.UserCreditProfile;
import com.loan.entity.UserIdentity;
import com.loan.entity.dto.CreditProfileDTO;
import com.loan.entity.vo.Result;
import com.loan.mapper.MockFinancialProfileMapper;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserCreditProfileMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.FinancialInfoService;
import com.loan.service.MultiSourceIngestService;
import com.loan.service.UserCreditProfileService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * 经济信息获取服务实现
 *
 * 设计说明：
 *  - 模拟库按【手机号】匹配（测试库中身份证重复，不可作主键）；
 *  - 无记录时按手机号**确定性生成**并入库，保证任何用户都能体验，且同一用户结果稳定；
 *  - 应用时复用 UserCreditProfileService（自动重算特征与评分），并把数据派生为
 *    credit / operator / social 多源特征写入 user_multi_source_data。
 */
@Service
public class FinancialInfoServiceImpl implements FinancialInfoService {

    private static final Logger log = LoggerFactory.getLogger(FinancialInfoServiceImpl.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
            .registerModule(new JavaTimeModule());

    @Resource
    private MockFinancialProfileMapper mockMapper;

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserCreditProfileMapper profileMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Resource
    private UserCreditProfileService userCreditProfileService;

    @Resource
    private MultiSourceIngestService multiSourceIngestService;

    // ==================== 1. 预览 ====================

    @Override
    public Result<?> preview(String userId) {
        MockFinancialProfile mock = findOrCreate(userId);
        if (mock == null) {
            return Result.error(400, "未找到用户信息，请先登录");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("dataSource", mock.getDataSource());
        data.put("dataSourceDesc", "MOCK_BANK".equals(mock.getDataSource())
                ? "模拟银行/征信数据" : "系统按身份自动生成的模拟数据");
        data.put("realName", mock.getRealName());
        data.put("phone", mock.getPhone());

        // 收入
        Map<String, Object> income = new LinkedHashMap<>();
        income.put("monthlyIncome", mock.getMonthlyIncome());
        income.put("incomeSource", mock.getIncomeSource());
        income.put("bankFlowMonths", mock.getBankFlowMonths());
        data.put("income", income);

        // 负债
        Map<String, Object> debt = new LinkedHashMap<>();
        debt.put("creditCardLimit", mock.getCreditCardLimit());
        debt.put("creditCardUsed", mock.getCreditCardUsed());
        debt.put("creditCardUtilization", ratio(mock.getCreditCardUsed(), mock.getCreditCardLimit()));
        debt.put("existingLoanCount", mock.getExistingLoanCount());
        debt.put("monthlyDebtPayment", mock.getMonthlyDebtPayment());
        debt.put("debtToIncome", ratio(mock.getMonthlyDebtPayment(), mock.getMonthlyIncome()));
        debt.put("overdueCount2y", mock.getOverdueCount2y());
        debt.put("queryCount3m", mock.getQueryCount3m());
        data.put("debt", debt);

        // 资产 / 社保
        Map<String, Object> asset = new LinkedHashMap<>();
        asset.put("hasHouse", mock.getHasHouse());
        asset.put("hasCar", mock.getHasCar());
        asset.put("depositBalance", mock.getDepositBalance());
        asset.put("socialContribMonths", mock.getSocialContribMonths());
        asset.put("socialBase", mock.getSocialBase());
        asset.put("operatorMonths", mock.getOperatorMonths());
        data.put("asset", asset);

        // 职业
        Map<String, Object> employment = new LinkedHashMap<>();
        employment.put("companyName", mock.getCompanyName());
        employment.put("position", mock.getPosition());
        employment.put("industryType", mock.getIndustryType());
        employment.put("workYears", mock.getWorkYears());
        employment.put("employmentType", mock.getEmploymentType());
        data.put("employment", employment);

        // 将派生的多源特征一并返回，便于前端/测试直观看到"会写入什么"
        data.put("derivedMultiSource", buildMultiSourceFields(mock));
        return Result.success(data);
    }

    // ==================== 2. 应用 ====================

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> apply(String userId, CreditProfileDTO provided) {
        MockFinancialProfile mock = findOrCreate(userId);
        if (mock == null) {
            return Result.error(400, "未找到用户信息，请先登录");
        }

        UserCreditProfile existing = profileMapper.selectByUserId(userId);
        // App 端"获取后经用户编辑确认"的数据优先；未提供时用模拟库自动组装
        CreditProfileDTO dto = (provided != null) ? provided : buildDtoFromMock(mock, existing, userId);
        if (dto.getName() == null && existing != null) {
            dto.setName(existing.getName());
        }
        if (dto.getPhone() == null) {
            dto.setPhone(mock.getPhone());
        }
        if ((dto.getIdCard() == null || dto.getIdCard().isBlank()) && existing != null) {
            dto.setIdCard(existing.getIdCard());
        }
        if (dto.getIdCard() == null || dto.getIdCard().isBlank()) {
            return Result.error(400, "请先完成实名认证（KYC）后再获取经济信息");
        }

        Result<?> profileResult;
        boolean created = (existing == null);
        if (created) {
            profileResult = userCreditProfileService.createProfile(userId, dto);
        } else {
            profileResult = userCreditProfileService.updateProfile(userId, dto);
        }
        if (profileResult == null || profileResult.getCode() != 200) {
            return profileResult == null ? Result.error(500, "档案写入失败") : profileResult;
        }

        Map<String, Object> ingested = ingestMultiSource(userId, mock);
        UserCreditProfile after = profileMapper.selectByUserId(userId);

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("profileCreated", created);
        data.put("creditScore", after == null ? null : after.getCreditScore());
        data.put("monthlyIncome", after == null ? null : after.getMonthlyIncome());
        data.put("debtRatio", after == null ? null : after.getDebtRatio());
        data.put("revolvingUtil", after == null ? null : after.getRevolvingUtil());
        data.put("creditLines", after == null ? null : after.getCreditLines());
        // 说明：引擎的 debt_ratio = Σ(负债 monthlyPayment) / 月收入；而信用卡记录的 monthlyPayment
        // 被复用承载"已用额度"（见 FeatureTransformationServiceImpl.calculateRevolvingUtil 的注释），
        // 因此引擎口径的 debt_ratio 会偏高。这里额外给出真实 DTI（月还款 / 月收入）供对照。
        data.put("realDebtToIncome", ratio(mock.getMonthlyDebtPayment(), mock.getMonthlyIncome()));
        data.put("multiSourceIngested", ingested);
        data.put("message", profileResult.getMsg());
        log.info("📥 [经济信息应用] 用户 {} 已回填档案并派生多源特征: {}", userId, ingested.keySet());
        return Result.success(data);
    }

    // ==================== 3. 管理端 ====================

    @Override
    public Result<?> listMock() {
        List<MockFinancialProfile> list = mockMapper.selectAll();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", mockMapper.countAll());
        data.put("list", list);
        return Result.success(data);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> upsertMock(MockFinancialProfile profile) {
        if (profile == null || profile.getMatchKey() == null || profile.getMatchKey().isBlank()) {
            return Result.error(400, "matchKey（手机号）不能为空");
        }
        if (profile.getPhone() == null) {
            profile.setPhone(profile.getMatchKey());
        }
        if (profile.getDataSource() == null) {
            profile.setDataSource("MOCK_BANK");
        }
        MockFinancialProfile exists = mockMapper.selectByMatchKey(profile.getMatchKey());
        if (exists == null) {
            mockMapper.insert(profile);
            return Result.success("已新增模拟金融信息：" + profile.getMatchKey());
        }
        mockMapper.updateByMatchKey(profile);
        return Result.success("已更新模拟金融信息：" + profile.getMatchKey());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Result<?> deleteMock(Long id) {
        if (id == null) {
            return Result.error(400, "id 不能为空");
        }
        mockMapper.deleteById(id);
        return Result.success("已删除");
    }

    // ==================== 内部方法 ====================

    /** 按手机号查模拟库；未命中则确定性生成并入库 */
    private MockFinancialProfile findOrCreate(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null || user.getPhone() == null) {
            return null;
        }
        MockFinancialProfile mock = mockMapper.selectByMatchKey(user.getPhone());
        if (mock != null) {
            return mock;
        }
        mock = generateByPhone(user.getPhone(), user.getRealName());
        try {
            mockMapper.insert(mock);
            log.info("🔧 为 {} 自动生成模拟金融信息（确定性，seed=手机号）", user.getPhone());
        } catch (Exception e) {
            log.warn("⚠️ 自动生成模拟金融信息入库失败（不影响预览）: {}", e.getMessage());
        }
        return mock;
    }

    /** 按手机号确定性生成（同一手机号结果稳定；按"资质档位"联动，保证字段间相互一致） */
    private MockFinancialProfile generateByPhone(String phone, String realName) {
        Random rnd = new Random(Math.abs(phone.hashCode()));
        MockFinancialProfile m = new MockFinancialProfile();
        m.setMatchKey(phone);
        m.setPhone(phone);
        m.setRealName(realName);

        // 先按种子定"资质档位"，再在档位区间内取值 —— 避免出现"高收入 + 多次逾期"这类不自洽的组合
        int dice = rnd.nextInt(100);
        int incMin, incMax, loanMax, debtMin, debtMax, overdueMax, qMin, qMax, socMin, socMax, usedMin, usedMax;
        if (dice < 20) {                 // 优质 20%
            incMin = 24000; incMax = 35001; loanMax = 1; debtMin = 1500; debtMax = 3000;
            overdueMax = 0; qMin = 0; qMax = 2; socMin = 60; socMax = 96; usedMin = 5; usedMax = 25;
        } else if (dice < 55) {          // 良好 35%
            incMin = 15000; incMax = 24000; loanMax = 2; debtMin = 2000; debtMax = 4000;
            overdueMax = 0; qMin = 2; qMax = 5; socMin = 36; socMax = 72; usedMin = 10; usedMax = 40;
        } else if (dice < 85) {          // 中等 30%
            incMin = 9000; incMax = 15000; loanMax = 3; debtMin = 2500; debtMax = 5000;
            overdueMax = 1; qMin = 4; qMax = 9; socMin = 12; socMax = 48; usedMin = 30; usedMax = 70;
        } else {                          // 较差 15%
            incMin = 4000; incMax = 9000; loanMax = 4; debtMin = 3000; debtMax = 6000;
            overdueMax = 4; qMin = 8; qMax = 20; socMin = 0; socMax = 12; usedMin = 60; usedMax = 95;
        }

        int income = incMin + rnd.nextInt(Math.max(1, (incMax - incMin) / 500)) * 500;
        int limit = income * (2 + rnd.nextInt(4));                                    // 月收入 2~5 倍
        int used = (int) (limit * (usedMin + rnd.nextInt(Math.max(1, usedMax - usedMin))) / 100.0);
        int loans = rnd.nextInt(loanMax + 1);                                          // 0~loanMax 笔
        int monthlyDebt = debtMin + rnd.nextInt(Math.max(1, (debtMax - debtMin) / 100)) * 100;
        int overdue = overdueMax == 0 ? 0 : rnd.nextInt(overdueMax + 1);
        int query3m = qMin + rnd.nextInt(Math.max(1, qMax - qMin + 1));
        int social = socMin + rnd.nextInt(Math.max(1, socMax - socMin + 1));

        m.setMonthlyIncome(BigDecimal.valueOf(income));
        m.setIncomeSource(rnd.nextInt(5) == 0 ? "BUSINESS" : "SALARY");
        m.setBankFlowMonths(6 + rnd.nextInt(19));
        m.setCreditCardLimit(BigDecimal.valueOf(limit));
        m.setCreditCardUsed(BigDecimal.valueOf(used));
        m.setExistingLoanCount(loans);
        m.setMonthlyDebtPayment(BigDecimal.valueOf(monthlyDebt));
        m.setOverdueCount2y(overdue);
        m.setQueryCount3m(query3m);
        m.setHasHouse(dice < 55 ? rnd.nextInt(2) == 1 : false);                        // 优质/良好更可能有房
        m.setHasCar(dice < 55 && rnd.nextInt(3) == 0);
        m.setDepositBalance(BigDecimal.valueOf((long) rnd.nextInt(60) * 5000));
        m.setSocialContribMonths(social);
        m.setSocialBase(BigDecimal.valueOf(income).multiply(BigDecimal.valueOf(0.7)));
        m.setOperatorMonths(12 + rnd.nextInt(109));
        m.setCompanyName("模拟企业" + (100 + rnd.nextInt(900)));
        m.setPosition(dice < 20 ? "主管" : "职员");
        m.setIndustryType("其他");
        m.setWorkYears(dice < 20 ? 5 + rnd.nextInt(11) : 1 + rnd.nextInt(10));
        m.setEmploymentType("FULL_TIME");
        m.setDataSource("AUTO_GEN");
        return m;
    }

    /** 由模拟金融信息构造信用档案 DTO（基本信息沿用已有档案/KYC） */
    private CreditProfileDTO buildDtoFromMock(MockFinancialProfile mock, UserCreditProfile existing, String userId) {
        CreditProfileDTO dto = new CreditProfileDTO();
        dto.setName(existing != null && existing.getName() != null ? existing.getName() : mock.getRealName());
        dto.setPhone(mock.getPhone() != null ? mock.getPhone()
                : (existing != null ? existing.getPhone() : null));
        dto.setEmail(existing != null ? existing.getEmail() : null);
        dto.setGender(existing != null && existing.getGender() != null ? existing.getGender() : "未知");
        dto.setEducation(existing != null ? existing.getEducation() : null);
        dto.setOccupation(mock.getPosition());

        // 身份证：优先已有档案 → 再取 KYC 记录
        String idCard = existing != null ? existing.getIdCard() : null;
        if (idCard == null || idCard.isBlank()) {
            UserIdentity identity = userIdentityMapper.selectByUserId(userId);
            if (identity != null) {
                idCard = identity.getIdCardNumber();
                if (dto.getName() == null) {
                    dto.setName(identity.getUserRealName());
                }
            }
        }
        dto.setIdCard(idCard);

        // 出生日期：沿用档案
        if (existing != null && existing.getBirthDate() != null) {
            dto.setBirthDate(existing.getBirthDate().toInstant()
                    .atZone(ZoneId.systemDefault()).toLocalDate());
        }

        // 家庭信息：沿用已有（模拟库不含家庭成员）
        CreditProfileDTO.FamilyInfo family = parseJson(existing == null ? null : existing.getFamilyInfo(),
                CreditProfileDTO.FamilyInfo.class);
        dto.setFamilyInfo(family != null ? family : new CreditProfileDTO.FamilyInfo());

        // 财务信息 / 职业信息：由模拟库生成
        dto.setFinancialInfo(buildFinancial(mock));
        dto.setEmploymentInfo(buildEmployment(mock));
        return dto;
    }

    /** 财务信息：收入来源 + 银行流水 + 资产 + 负债 */
    private CreditProfileDTO.FinancialInfo buildFinancial(MockFinancialProfile mock) {
        CreditProfileDTO.FinancialInfo fin = new CreditProfileDTO.FinancialInfo();

        // 收入来源（SALARY 在月收入口径中会被过滤，避免与 monthlySalary 重复累加）
        if (mock.getMonthlyIncome() != null) {
            CreditProfileDTO.IncomeSource src = new CreditProfileDTO.IncomeSource();
            src.setType(mock.getIncomeSource() == null ? "SALARY" : mock.getIncomeSource());
            src.setMonthlyAmount(mock.getMonthlyIncome());
            src.setDescription("银行流水推算（模拟数据）");
            fin.setIncomeSources(new ArrayList<>(List.of(src)));
        }

        // 银行流水（按月均近似生成最近 6 个月）
        if (mock.getBankFlowMonths() != null && mock.getMonthlyIncome() != null) {
            CreditProfileDTO.BankStatement st = new CreditProfileDTO.BankStatement();
            st.setBankName("模拟银行");
            st.setAccountNumber(maskAccount(mock.getPhone()));
            List<CreditProfileDTO.MonthlyBalance> months = new ArrayList<>();
            BigDecimal debt = mock.getMonthlyDebtPayment() == null ? BigDecimal.ZERO : mock.getMonthlyDebtPayment();
            for (int i = 1; i <= 6; i++) {
                CreditProfileDTO.MonthlyBalance mb = new CreditProfileDTO.MonthlyBalance();
                mb.setMonth(YearMonth.now().minusMonths(i).toString());
                mb.setAverageBalance(mock.getMonthlyIncome().multiply(new BigDecimal("0.8")));
                mb.setTotalIncome(mock.getMonthlyIncome());
                mb.setTotalExpense(debt);
                months.add(mb);
            }
            st.setMonthlyBalances(months);
            fin.setBankStatements(new ArrayList<>(List.of(st)));
        }

        // 资产
        List<CreditProfileDTO.AssetInfo> assets = new ArrayList<>();
        if (Boolean.TRUE.equals(mock.getHasHouse())) {
            assets.add(asset("HOUSE", "房产（模拟）", new BigDecimal("1200000")));
        }
        if (Boolean.TRUE.equals(mock.getHasCar())) {
            assets.add(asset("CAR", "车辆（模拟）", new BigDecimal("150000")));
        }
        if (mock.getDepositBalance() != null && mock.getDepositBalance().compareTo(BigDecimal.ZERO) > 0) {
            assets.add(asset("DEPOSIT", "存款（模拟）", mock.getDepositBalance()));
        }
        if (!assets.isEmpty()) {
            fin.setAssetInfo(assets);
        }

        // 负债：信用卡（totalAmount=额度，monthlyPayment 按既有引擎口径填入"已用额度"以计算使用率）+ 在贷笔数
        List<CreditProfileDTO.LiabilityInfo> liabilities = new ArrayList<>();
        if (mock.getCreditCardLimit() != null && mock.getCreditCardLimit().compareTo(BigDecimal.ZERO) > 0) {
            CreditProfileDTO.LiabilityInfo cc = new CreditProfileDTO.LiabilityInfo();
            cc.setLiabilityType("CREDIT_CARD");
            cc.setCreditor("模拟银行");
            cc.setTotalAmount(mock.getCreditCardLimit());
            // 说明：评分引擎的 revolving_util = Σ信用卡 monthlyPayment / Σ信用卡 totalAmount（注释已注明用该字段代指已用额度）
            cc.setMonthlyPayment(mock.getCreditCardUsed() == null ? BigDecimal.ZERO : mock.getCreditCardUsed());
            cc.setDueDate(LocalDate.now().plusYears(3));
            liabilities.add(cc);
        }
        int loanCount = mock.getExistingLoanCount() == null ? 0 : mock.getExistingLoanCount();
        if (loanCount > 0 && mock.getMonthlyDebtPayment() != null) {
            BigDecimal per = mock.getMonthlyDebtPayment().divide(BigDecimal.valueOf(loanCount), 2, RoundingMode.HALF_UP);
            for (int i = 1; i <= loanCount; i++) {
                CreditProfileDTO.LiabilityInfo loan = new CreditProfileDTO.LiabilityInfo();
                loan.setLiabilityType("LOAN");
                loan.setCreditor("模拟银行" + i);
                loan.setTotalAmount(per.multiply(new BigDecimal("24")));
                loan.setMonthlyPayment(per);
                loan.setDueDate(LocalDate.now().plusYears(2));
                liabilities.add(loan);
            }
        }
        if (!liabilities.isEmpty()) {
            fin.setLiabilityInfo(liabilities);
        }
        return fin;
    }

    private CreditProfileDTO.AssetInfo asset(String type, String desc, BigDecimal value) {
        CreditProfileDTO.AssetInfo a = new CreditProfileDTO.AssetInfo();
        a.setAssetType(type);
        a.setDescription(desc);
        a.setEstimatedValue(value);
        return a;
    }

    /** 职业信息：由模拟库生成 */
    private CreditProfileDTO.EmploymentInfo buildEmployment(MockFinancialProfile mock) {
        CreditProfileDTO.EmploymentInfo emp = new CreditProfileDTO.EmploymentInfo();
        emp.setCompanyName(mock.getCompanyName());
        emp.setPosition(mock.getPosition());
        emp.setIndustryType(mock.getIndustryType());
        emp.setWorkDurationYears(mock.getWorkYears());
        emp.setEmploymentType(mock.getEmploymentType());
        // 月收入主口径：engine 的 monthly_income = monthlySalary + additionalIncome + 非 SALARY 收入
        emp.setMonthlySalary(mock.getMonthlyIncome());
        emp.setAdditionalIncome(BigDecimal.ZERO);
        return emp;
    }

    /** 派生并写入 credit / operator / social 多源特征 */
    private Map<String, Object> ingestMultiSource(String userId, MockFinancialProfile mock) {
        Map<String, Object> result = new LinkedHashMap<>();

        // credit 源
        Map<String, Object> credit = new LinkedHashMap<>();
        putIfNotNull(credit, "credit_query_cnt_3m", mock.getQueryCount3m());
        putIfNotNull(credit, "debt_to_income", ratio(mock.getMonthlyDebtPayment(), mock.getMonthlyIncome()));
        int overdue = mock.getOverdueCount2y() == null ? 0 : mock.getOverdueCount2y();
        credit.put("hist_overdue_cnt_m1", overdue / 3);
        credit.put("hist_overdue_cnt_m2", overdue / 4);
        credit.put("hist_overdue_cnt_m3", overdue / 5);
        credit.put("credit_query_cnt_6m", mock.getQueryCount3m() == null ? 0 : mock.getQueryCount3m() * 2);
        result.put("credit", multiSourceIngestService.ingestFields(userId, "credit", credit));

        // operator 源
        Map<String, Object> operator = new LinkedHashMap<>();
        putIfNotNull(operator, "operator_realname_months", mock.getOperatorMonths());
        putIfNotNull(operator, "operator_tenure_months", mock.getOperatorMonths());
        operator.put("operator_pay_stability", 12 + (mock.getWorkYears() == null ? 0 : mock.getWorkYears()));
        result.put("operator", multiSourceIngestService.ingestFields(userId, "operator", operator));

        // social 源
        Map<String, Object> social = new LinkedHashMap<>();
        putIfNotNull(social, "social_contrib_months", mock.getSocialContribMonths());
        putIfNotNull(social, "social_base", mock.getSocialBase());
        social.put("social_employer_cnt", mock.getExistingLoanCount() == null ? 1 : 1);
        result.put("social", multiSourceIngestService.ingestFields(userId, "social", social));

        // device / behavior 由 App 采集上报，不在此覆盖
        result.put("note", "device/behavior 源由 App 端上报，本次不覆盖");
        return result;
    }

    /** 供预览展示：将要写入的多源特征 */
    private Map<String, Object> buildMultiSourceFields(MockFinancialProfile mock) {
        Map<String, Object> all = new LinkedHashMap<>();
        int overdue = mock.getOverdueCount2y() == null ? 0 : mock.getOverdueCount2y();
        Map<String, Object> credit = new LinkedHashMap<>();
        credit.put("credit_query_cnt_3m", mock.getQueryCount3m());
        credit.put("debt_to_income", ratio(mock.getMonthlyDebtPayment(), mock.getMonthlyIncome()));
        credit.put("hist_overdue_cnt_m1", overdue / 3);
        credit.put("hist_overdue_cnt_m2", overdue / 4);
        credit.put("hist_overdue_cnt_m3", overdue / 5);
        all.put("credit", credit);
        Map<String, Object> operator = new LinkedHashMap<>();
        operator.put("operator_realname_months", mock.getOperatorMonths());
        operator.put("operator_pay_stability", 12 + (mock.getWorkYears() == null ? 0 : mock.getWorkYears()));
        all.put("operator", operator);
        Map<String, Object> social = new LinkedHashMap<>();
        social.put("social_contrib_months", mock.getSocialContribMonths());
        social.put("social_base", mock.getSocialBase());
        all.put("social", social);
        return all;
    }

    private void putIfNotNull(Map<String, Object> map, String key, Object value) {
        if (value != null) {
            map.put(key, value);
        }
    }

    private BigDecimal ratio(BigDecimal numerator, BigDecimal denominator) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return numerator.divide(denominator, 4, RoundingMode.HALF_UP);
    }

    private String maskAccount(String phone) {
        if (phone == null || phone.length() < 4) {
            return "****";
        }
        return "****" + phone.substring(phone.length() - 4);
    }

    private <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("⚠️ 解析档案 JSON 失败: {}", e.getMessage());
            return null;
        }
    }
}

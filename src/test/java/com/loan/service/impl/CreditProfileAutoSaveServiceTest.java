package com.loan.service.impl;

import com.loan.entity.UserCreditProfile;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.entity.dto.UserProfileDTO;
import com.loan.mapper.UserCreditProfileMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.math.BigDecimal;
import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link CreditProfileAutoSaveService} 的单元测试（阶段四收尾）
 *
 * <p><b>为什么值得测</b>：这个类覆盖率只有 4%（47 行），但它是**写库**的 ——
 * 写错了用户的信用档案就被污染了，而且下次申请会用到错误的数据。
 *
 * <p><b>测什么</b>：
 * <ul>
 *   <li><b>新建 vs 更新</b>：档案不存在时走 insert、存在时走 update（走错会主键冲突或丢数据）</li>
 *   <li><b>JSON 序列化</b>：家庭/财务/职业信息是 JSON 存的，序列化失败不能影响主流程</li>
 *   <li><b>birthDate 为 null</b>：不能 NPE（`Date.valueOf(null)` 会抛异常）</li>
 *   <li><b>异常吞掉</b>：档案保存失败绝不能阻断申请（原设计如此）</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@DisplayName("信用档案自动保存 CreditProfileAutoSaveService")
class CreditProfileAutoSaveServiceTest {

    @Mock private UserCreditProfileMapper userCreditProfileMapper;

    @InjectMocks private CreditProfileAutoSaveService service;

    private UserProfileDTO profile() {
        UserProfileDTO p = new UserProfileDTO();
        p.setAge(35);
        p.setDebtRatio(new BigDecimal("0.4"));
        p.setMonthlyIncome(new BigDecimal("8000"));
        p.setCreditLines(6);
        p.setDependents(2);
        p.setRevolvingUtil(new BigDecimal("0.3"));
        return p;
    }

    private UserBasicInfoDTO basicInfo() {
        UserBasicInfoDTO dto = new UserBasicInfoDTO();
        dto.setProductId("product_001");
        dto.setApplyAmount(new BigDecimal("10000"));
        dto.setApplyTerm(12);
        return dto;
    }

    @Nested
    @DisplayName("新建 vs 更新")
    class CreateOrUpdate {

        @Test
        @DisplayName("档案不存在 → insert（不是 update）")
        void insertWhenAbsent() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            service.saveOrUpdate("u1", basicInfo(), profile(), 720);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            verify(userCreditProfileMapper, never()).update(any());

            UserCreditProfile saved = cap.getValue();
            assertThat(saved.getUserId()).isEqualTo("u1");
            assertThat(saved.getCreateTime()).isNotNull();      // 新记录必须设创建时间
            assertThat(saved.getCreditScore()).isEqualTo(720);
        }

        @Test
        @DisplayName("档案已存在 → update（且保留原 createTime，不重置）")
        void updateWhenExists() {
            UserCreditProfile existing = new UserCreditProfile();
            existing.setUserId("u1");
            existing.setId(42L);
            java.util.Date originalCreate = new java.util.Date(1700000000000L);
            existing.setCreateTime(originalCreate);
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(existing);

            service.saveOrUpdate("u1", basicInfo(), profile(), 700);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).update(cap.capture());
            verify(userCreditProfileMapper, never()).insert(any());

            assertThat(cap.getValue().getCreateTime()).isSameAs(originalCreate);
            assertThat(cap.getValue().getUpdateTime()).isNotNull();
        }
    }

    @Nested
    @DisplayName("字段复制")
    class FieldCopy {

        @Test
        @DisplayName("评分特征 6 个字段 + creditScore 全部写入")
        void scoreFeatures() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            service.saveOrUpdate("u1", basicInfo(), profile(), 715);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            UserCreditProfile p = cap.getValue();

            assertThat(p.getAge()).isEqualTo(35);
            assertThat(p.getDebtRatio()).isEqualByComparingTo("0.4");
            assertThat(p.getMonthlyIncome()).isEqualByComparingTo("8000");
            assertThat(p.getCreditLines()).isEqualTo(6);
            assertThat(p.getDependents()).isEqualTo(2);
            assertThat(p.getRevolvingUtil()).isEqualByComparingTo("0.3");
            assertThat(p.getCreditScore()).isEqualTo(715);
        }

        @Test
        @DisplayName("个人信息含 birthDate（LocalDate → java.sql.Date）")
        void personalInfoWithBirthDate() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();
            UserBasicInfoDTO.PersonalInfo pi = new UserBasicInfoDTO.PersonalInfo();
            pi.setName("张三");
            pi.setIdCard("350102199001011234");
            pi.setPhone("13900009001");
            pi.setEmail("z@x.com");
            pi.setGender("男");
            pi.setEducation("本科");
            pi.setOccupation("工程师");
            pi.setBirthDate(LocalDate.of(1990, 1, 1));
            dto.setPersonalInfo(pi);

            service.saveOrUpdate("u1", dto, profile(), 700);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            UserCreditProfile p = cap.getValue();

            assertThat(p.getName()).isEqualTo("张三");
            assertThat(p.getIdCard()).isEqualTo("350102199001011234");
            assertThat(p.getEmail()).isEqualTo("z@x.com");
            assertThat(p.getBirthDate()).isNotNull();
            assertThat(p.getBirthDate().toString()).isEqualTo("1990-01-01");
        }

        @Test
        @DisplayName("birthDate 为 null → 不 NPE（Date.valueOf(null) 会抛异常）")
        void personalInfoWithoutBirthDate() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();
            UserBasicInfoDTO.PersonalInfo pi = new UserBasicInfoDTO.PersonalInfo();
            pi.setName("李四");
            pi.setBirthDate(null);
            dto.setPersonalInfo(pi);

            assertThatCode(() -> service.saveOrUpdate("u1", dto, profile(), 700))
                    .doesNotThrowAnyException();

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            assertThat(cap.getValue().getName()).isEqualTo("李四");
            assertThat(cap.getValue().getBirthDate()).isNull();
        }

        @Test
        @DisplayName("personalInfo 为 null → 不覆盖已有个人信息，不 NPE")
        void personalInfoNull() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();
            dto.setPersonalInfo(null);

            assertThatCode(() -> service.saveOrUpdate("u1", dto, profile(), 700))
                    .doesNotThrowAnyException();

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            assertThat(cap.getValue().getName()).isNull();
        }
    }

    @Nested
    @DisplayName("嵌套结构 JSON 序列化")
    class JsonSerialization {

        @Test
        @DisplayName("家庭信息 → JSON 字符串（含 LocalDate，需 JavaTimeModule）")
        void familyInfoJson() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();
            UserBasicInfoDTO.FamilyInfo fi = new UserBasicInfoDTO.FamilyInfo();
            fi.setMaritalStatus("已婚");
            dto.setFamilyInfo(fi);

            service.saveOrUpdate("u1", dto, profile(), 700);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            assertThat(cap.getValue().getFamilyInfo()).contains("已婚");
        }

        @Test
        @DisplayName("财务 + 职业信息都能序列化")
        void financialAndEmployment() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();
            UserBasicInfoDTO.EmploymentInfo ei = new UserBasicInfoDTO.EmploymentInfo();
            ei.setCompanyName("某公司");
            ei.setPosition("工程师");
            ei.setMonthlySalary(new BigDecimal("15000"));
            dto.setEmploymentInfo(ei);

            service.saveOrUpdate("u1", dto, profile(), 700);

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            assertThat(cap.getValue().getEmploymentInfo()).contains("某公司");
        }

        @Test
        @DisplayName("三块嵌套信息都为 null → 不设置 JSON 字段，不 NPE")
        void allNestedNull() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);

            UserBasicInfoDTO dto = basicInfo();   // family/financial/employment 都是 null

            assertThatCode(() -> service.saveOrUpdate("u1", dto, profile(), 700))
                    .doesNotThrowAnyException();

            ArgumentCaptor<UserCreditProfile> cap = ArgumentCaptor.forClass(UserCreditProfile.class);
            verify(userCreditProfileMapper).insert(cap.capture());
            assertThat(cap.getValue().getFamilyInfo()).isNull();
            assertThat(cap.getValue().getFinancialInfo()).isNull();
            assertThat(cap.getValue().getEmploymentInfo()).isNull();
        }
    }

    @Nested
    @DisplayName("异常吞掉（档案保存失败不能阻断申请）")
    class FailureSwallowed {

        @Test
        @DisplayName("查询档案时抛异常 → 吞掉，不向上传播")
        void selectThrows() {
            when(userCreditProfileMapper.selectByUserId("u1"))
                    .thenThrow(new RuntimeException("DB 连接失败"));

            assertThatCode(() -> service.saveOrUpdate("u1", basicInfo(), profile(), 700))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("insert 抛异常 → 吞掉，不向上传播")
        void insertThrows() {
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(null);
            when(userCreditProfileMapper.insert(any()))
                    .thenThrow(new RuntimeException("唯一键冲突"));

            assertThatCode(() -> service.saveOrUpdate("u1", basicInfo(), profile(), 700))
                    .doesNotThrowAnyException();
        }

        @Test
        @DisplayName("update 抛异常 → 吞掉，不向上传播")
        void updateThrows() {
            UserCreditProfile existing = new UserCreditProfile();
            existing.setUserId("u1");
            when(userCreditProfileMapper.selectByUserId("u1")).thenReturn(existing);
            when(userCreditProfileMapper.update(any()))
                    .thenThrow(new RuntimeException("死锁"));

            assertThatCode(() -> service.saveOrUpdate("u1", basicInfo(), profile(), 700))
                    .doesNotThrowAnyException();
        }
    }
}

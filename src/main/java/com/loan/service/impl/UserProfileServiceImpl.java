package com.loan.service.impl;

import com.loan.entity.SysUser;
import com.loan.entity.UserIdentity;
import com.loan.entity.dto.UserBasicInfoDTO;
import com.loan.mapper.SysUserMapper;
import com.loan.mapper.UserIdentityMapper;
import com.loan.service.UserProfileService;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class UserProfileServiceImpl implements UserProfileService {

    private static final Logger log = LoggerFactory.getLogger(UserProfileServiceImpl.class);

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Override
    public UserBasicInfoDTO buildUserBasicInfoFromKyc(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            throw new RuntimeException("用户不存在");
        }

        UserIdentity identity = userIdentityMapper.selectByUserId(userId);

        UserBasicInfoDTO basicInfo = new UserBasicInfoDTO();

        UserBasicInfoDTO.PersonalInfo personalInfo = new UserBasicInfoDTO.PersonalInfo();
        personalInfo.setName(user.getRealName());
        personalInfo.setPhone(user.getPhone());

        if (identity != null) {
            personalInfo.setIdCard(identity.getIdCardNumber());
        }

        basicInfo.setPersonalInfo(personalInfo);

        log.info("✅ 从KYC数据构建用户基础信息: userId={}", userId);

        return basicInfo;
    }

    @Override
    public boolean isKycCompleted(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user != null && user.getKycStatus() != null && user.getKycStatus() == 1;
    }

    @Override
    public String getVerifiedRealName(String userId) {
        SysUser user = sysUserMapper.selectById(userId);
        return user != null ? user.getRealName() : null;
    }

    @Override
    public String getVerifiedIdCard(String userId) {
        UserIdentity identity = userIdentityMapper.selectByUserId(userId);
        return identity != null ? identity.getIdCardNumber() : null;
    }
}

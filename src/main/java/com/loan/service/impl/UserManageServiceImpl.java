package com.loan.service.impl;

import com.loan.entity.SysUser;
import com.loan.entity.UserCreditScore;
import com.loan.entity.UserIdentity;
import com.loan.entity.vo.*;
import com.loan.mapper.*;
import com.loan.service.UserManageService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class UserManageServiceImpl implements UserManageService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private UserIdentityMapper userIdentityMapper;

    @Resource
    private UserCreditScoreMapper userCreditScoreMapper;

    @Resource
    private UserBankCardMapper userBankCardMapper;

    @Resource
    private LoanApplicationMapper loanApplicationMapper;

    @Resource
    private LoanProductMapper loanProductMapper;

    @Resource
    private MonthlyRepaymentMapper monthlyRepaymentMapper;

    @Override
    public Result<PageResult<UserItemVO>> listUsers(String phone, String name,
                                                     String startDate, String endDate,
                                                     String verifyStatus,
                                                     Integer page, Integer pageSize,
                                                     String sortBy, String sortOrder) {
        // 转换 verifyStatus 字符串为数据库整数
        Integer kycStatus = null;
        if (verifyStatus != null && !verifyStatus.isEmpty()) {
            switch (verifyStatus) {
                case "verified":
                    kycStatus = 1;
                    break;
                case "verifying":
                    kycStatus = 2;
                    break;
                case "unverified":
                    kycStatus = 0;
                    break;
                default:
                    break;
            }
        }

        Integer offset = (page - 1) * pageSize;
        List<UserItemVO> list = sysUserMapper.selectUserList(phone, name, startDate, endDate,
                kycStatus, sortBy, sortOrder, offset, pageSize);
        Long total = sysUserMapper.countUserList(phone, name, startDate, endDate, kycStatus);

        PageResult<UserItemVO> pageResult = new PageResult<>();
        pageResult.setList(list);
        pageResult.setTotal(total);
        pageResult.setPage(page);
        pageResult.setPageSize(pageSize);
        return Result.success(pageResult);
    }

    @Override
    public Result<UserDetailVO> getUserDetail(String userId) {
        // 查询基本用户信息
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            return Result.error(404, "用户不存在");
        }

        // 查询信用分
        UserCreditScore credit = userCreditScoreMapper.selectByUserId(userId);

        // 查询身份信息（已认证用户取真实姓名）
        UserIdentity identity = userIdentityMapper.selectByUserId(userId);

        // 组装 VO
        UserDetailVO vo = new UserDetailVO();
        vo.setUserId(user.getUserId());
        vo.setPhone(user.getPhone());
        // sys_user.real_name 作为昵称
        vo.setNickname(user.getRealName());
        // 已认证用户从 user_identity 取真实姓名，否则置空
        boolean verified = user.getKycStatus() != null && user.getKycStatus() == 1;
        vo.setName(verified && identity != null ? identity.getUserRealName() : null);
        // 格式化日期
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        vo.setRegisterTime(user.getCreateTime() != null ? sdf.format(user.getCreateTime()) : null);
        vo.setLastLoginTime(user.getUpdateTime() != null ? sdf.format(user.getUpdateTime()) : null);
        vo.setGender("");   // 暂未存储
        vo.setAge(null);    // 暂未存储
        // kycStatus: 0=unverified, 1=verified, 2=verifying
        if (user.getKycStatus() != null) {
            switch (user.getKycStatus()) {
                case 1: vo.setVerifyStatus("verified"); break;
                case 2: vo.setVerifyStatus("verifying"); break;
                default: vo.setVerifyStatus("unverified"); break;
            }
        } else {
            vo.setVerifyStatus("unverified");
        }
        vo.setCreditScore(credit != null ? credit.getCreditScore() : null);

        return Result.success(vo);
    }

    @Override
    public Result<UserVerificationVO> getUserVerification(String userId) {
        UserIdentity identity = userIdentityMapper.selectByUserId(userId);
        if (identity == null) {
            return Result.success(null);
        }

        UserVerificationVO vo = new UserVerificationVO();
        vo.setRealName(identity.getUserRealName());
        vo.setIdCard(identity.getIdCardNumber());
        vo.setVerifyTime(identity.getUpdateTime() != null ? identity.getUpdateTime().toString() : null);
        vo.setVerifyMethod("人脸识别+身份证");
        vo.setIdCardFront(identity.getIdCardFrontUrl());
        vo.setIdCardBack(""); // 身份证反面 URL 暂未存储
        vo.setFacePhoto(identity.getFaceCaptureUrl());

        return Result.success(vo);
    }

    @Override
    public Result<List<UserBankCardVO>> getUserBankCards(String userId) {
        List<UserBankCardVO> list = userBankCardMapper.selectUserBankCardVOByUserId(userId);
        return Result.success(list);
    }

    @Override
    public Result<List<CreditHistoryPointVO>> getCreditHistory(String userId) {
        List<CreditHistoryPointVO> list = userCreditScoreMapper.selectHistoryByUserId(userId);
        return Result.success(list);
    }

    @Override
    public Result<List<UserLoanRecordVO>> getUserLoans(String userId) {
        // 复用 LoanApplicationMapper 已有的 selectByUserId（返回 LoanApplicationVO）
        // 但 LoanApplicationVO 与 UserLoanRecordVO 字段不完全一致，需要转换
        List<LoanApplicationVO> loanVOs = loanApplicationMapper.selectByUserId(userId);
        if (loanVOs == null || loanVOs.isEmpty()) {
            return Result.success(Collections.emptyList());
        }

        List<UserLoanRecordVO> list = loanVOs.stream().map(loan -> {
            UserLoanRecordVO vo = new UserLoanRecordVO();
            vo.setApplicationId(loan.getApplicationId());
            vo.setProductName(loan.getProductName());
            vo.setApplyAmount(loan.getApplyAmount());
            vo.setApplyTerm(loan.getApplyTerm());
            // 转换状态: pending/approved/rejected/issued → pending/approved/rejected/disbursed
            String status = loan.getApplicationStatus();
            if ("issued".equals(status)) {
                vo.setStatus("disbursed");
            } else {
                vo.setStatus(status);
            }
            vo.setApplyTime(loan.getApplyTime() != null ? loan.getApplyTime().toString() : null);
            return vo;
        }).collect(Collectors.toList());

        return Result.success(list);
    }

    @Override
    public Result<List<RepaymentRecordVO>> getUserRepayments(String userId) {
        List<RepaymentRecordVO> list = monthlyRepaymentMapper.selectRepaymentRecordByUserId(userId);
        return Result.success(list);
    }
}

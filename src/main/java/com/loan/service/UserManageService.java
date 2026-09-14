package com.loan.service;

import com.loan.entity.vo.*;

import java.util.List;

public interface
UserManageService {

    Result<PageResult<UserItemVO>> listUsers(String phone, String name,
                                              String startDate, String endDate,
                                              String verifyStatus,
                                              Integer page, Integer pageSize,
                                              String sortBy, String sortOrder);

    Result<UserDetailVO> getUserDetail(String userId);

    Result<UserVerificationVO> getUserVerification(String userId);

    Result<List<UserBankCardVO>> getUserBankCards(String userId);

    Result<List<CreditHistoryPointVO>> getCreditHistory(String userId);

    Result<List<UserLoanRecordVO>> getUserLoans(String userId);

    Result<List<RepaymentRecordVO>> getUserRepayments(String userId);
}

package com.loan.service;
import com.loan.entity.SysUser;
import com.loan.entity.dto.UserChangePasswordDTO;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;

public interface SysUserService {
    Result<?> register(UserRegisterDTO registerDTO);
    Result<?> registerAdmin(UserRegisterDTO registerDTO);
    Result<?> login(UserLoginDTO loginDTO);
    Result<?> adminLogin(UserLoginDTO loginDTO);
    SysUser getUserById(String userId);
    Result<?> changePassword(String userId, UserChangePasswordDTO dto);

    /**
     * 切换账号启用/禁用状态
     */
    Result<?> toggleUserStatus(String targetUserId, String operatorId);
}
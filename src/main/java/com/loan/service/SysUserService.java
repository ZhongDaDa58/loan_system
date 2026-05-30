package com.loan.service;
import com.loan.entity.SysUser;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;

public interface SysUserService {
    Result<?> register(UserRegisterDTO registerDTO);
    Result<?> registerAdmin(UserRegisterDTO registerDTO);
    Result<?> login(UserLoginDTO loginDTO);
    Result<?> adminLogin(UserLoginDTO loginDTO);
    SysUser getUserById(String userId);
}
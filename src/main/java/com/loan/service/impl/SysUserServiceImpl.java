package com.loan.service.impl;
import com.loan.entity.SysUser;
import com.loan.entity.dto.UserLoginDTO;
import com.loan.entity.dto.UserRegisterDTO;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import com.loan.mapper.SysUserMapper;
import com.loan.service.SysUserService;
import com.loan.util.IdUtil;
import com.loan.util.JwtUtil;
import com.loan.util.PasswordUtil;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;


@Service
public class SysUserServiceImpl implements SysUserService {

    @Resource
    private SysUserMapper sysUserMapper;

    @Resource
    private JwtUtil jwtUtil;

    @Override
    public Result<?> register(UserRegisterDTO registerDTO) {
        // 手机号查重
        SysUser existUser = sysUserMapper.selectByPhone(registerDTO.getPhone());
        System.out.println("registering");
        if (existUser != null) {
            throw new BusinessException(400, "手机号已注册");
        }

        // 密码加密
        String encryptedPwd = PasswordUtil.encrypt(registerDTO.getPassword());

        // 构建用户对象
        SysUser user = new SysUser();
        user.setUserId(IdUtil.generateId());
        user.setPhone(registerDTO.getPhone());
        user.setPassword(encryptedPwd);
        user.setRealName(registerDTO.getRealName());
        user.setRole("user"); // 普通用户
        user.setStatus(1);

        // 插入数据库
        int rows = sysUserMapper.insert(user);
        if (rows != 1) {
            throw new BusinessException(500, "注册失败，请重试");
        }

        return Result.success("注册成功");
    }
    @Override
    public Result<?> registerAdmin(UserRegisterDTO registerDTO) {
        // 查重
        SysUser existUser = sysUserMapper.selectByPhone(registerDTO.getPhone());
        if (existUser != null) {
            throw new BusinessException(400, "手机号已注册");
        }

        // 加密密码
        String encryptedPwd = PasswordUtil.encrypt(registerDTO.getPassword());

        // 构建管理员对象
        SysUser user = new SysUser();
        user.setUserId(IdUtil.generateId());
        user.setPhone(registerDTO.getPhone());
        user.setPassword(encryptedPwd);
        user.setRealName(registerDTO.getRealName());
        user.setRole("admin"); // ⭐ 创建为管理员
        user.setStatus(1);

        int rows = sysUserMapper.insert(user);
        if (rows != 1) {
            throw new BusinessException(500, "创建失败，请重试");
        }

        return Result.success("审核员账号创建成功");
    }
    @Override
    public Result<?> login(UserLoginDTO loginDTO) {
        // 校验用户存在
        SysUser user = sysUserMapper.selectByPhone(loginDTO.getPhone());
        if (user == null) {
            throw new BusinessException(400, "账号不存在");
        }
        // ⭐ 校验角色：仅允许 user 角色登录
        if (!"user".equals(user.getRole())) {
            throw new BusinessException(403, "权限不足：该账号为审核员账号，请使用管理端登录");
        }
        // 校验密码
        if (!PasswordUtil.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "密码错误");
        }

        // 校验状态
        if (user.getStatus() != 1) {
            throw new BusinessException(403, "账号已禁用");
        }

        // 生成JWT令牌
        String token = jwtUtil.generateToken(user.getUserId(), user.getRole());

        // 脱敏返回
        user.setPassword(null);
        return Result.success(new java.util.HashMap<String, Object>() {{
            put("token", token);
            put("user", user);
        }});
    }
    @Override
    public Result<?> adminLogin(UserLoginDTO loginDTO) {
        // 校验用户存在
        SysUser user = sysUserMapper.selectByPhone(loginDTO.getPhone());
        if (user == null) {
            throw new BusinessException(400, "账号不存在");
        }

        // ⭐ 校验角色：仅允许 admin 角色登录
        if (!"admin".equals(user.getRole())) {
            throw new BusinessException(403, "权限不足：该账号为普通用户账号，请使用贷款端登录");
        }

        // 校验密码
        if (!PasswordUtil.matches(loginDTO.getPassword(), user.getPassword())) {
            throw new BusinessException(400, "密码错误");
        }

        // 校验状态
        if (user.getStatus() != 1) {
            throw new BusinessException(403, "账号已禁用");
        }

        // 生成 JWT 令牌
        String token = jwtUtil.generateToken(user.getUserId(), user.getRole());

        // 脱敏返回
        user.setPassword(null);
        return Result.success(new java.util.HashMap<String, Object>() {{
            put("token", token);
            put("user", user);
        }});
    }

    @Override
    public SysUser getUserById(String userId) {
        return sysUserMapper.selectById(userId);
    }
}
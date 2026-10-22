package com.loan.config;

import com.loan.entity.SysUser;
import com.loan.mapper.SysUserMapper;
import jakarta.annotation.Resource;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;

/**
 * 把 {@code sys_user} 接入 Spring Security（阶段四 4.1）
 *
 * <p><b>背景</b>：项目原来装了 {@code spring-boot-starter-security}，
 * 但 {@code SecurityFilterChain} 是 {@code anyRequest().permitAll()}，
 * 真正的鉴权靠手写的 {@code LoginInterceptor} —— 而它<b>只校验"是否登录"，从不校验角色</b>。
 * 结果：普通用户 token 能调用管理端接口（P-022）。
 *
 * <p><b>⚠️ 角色大小写</b>：库里 {@code sys_user.role} 存的是小写 {@code admin} / {@code user}（实测 24 user / 2 admin）。
 * Spring Security 的 {@code hasRole("ADMIN")} 实际匹配权限名 {@code ROLE_ADMIN}，
 * 所以这里必须 <b>转大写并加 ROLE_ 前缀</b>，否则永远匹配不上。
 */
@Service
public class DbUserDetailsService implements UserDetailsService {

    @Resource
    private SysUserMapper sysUserMapper;

    /**
     * @param username 在本项目里就是 userId（由 JWT 解析得到，见 JwtAuthenticationFilter）
     */
    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        SysUser u = sysUserMapper.selectById(username);
        if (u == null) {
            throw new UsernameNotFoundException("用户不存在: " + username);
        }
        if (u.getStatus() == null || u.getStatus() != 1) {
            // 抛出后由 JwtAuthenticationFilter 捕获 → 不设置认证 → Security 返回 401
            throw new UsernameNotFoundException("账号已禁用: " + username);
        }

        String role = (u.getRole() == null || u.getRole().isBlank())
                ? "user" : u.getRole().trim().toLowerCase();

        return User.withUsername(u.getUserId())
                // JWT 校验已通过，这里不参与密码比对；给个非空占位避免 NPE
                .password(u.getPassword() == null ? "-" : u.getPassword())
                .authorities("ROLE_" + role.toUpperCase())
                .build();
    }
}

package com.loan.config;

import com.loan.entity.SysUser;
import com.loan.service.SysUserService;
import com.loan.util.JwtUtil;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.servlet.HandlerInterceptor;


import java.io.PrintWriter;

public class LoginInterceptor implements HandlerInterceptor {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private SysUserService sysUserService;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        response.setContentType("application/json;charset=utf-8");

        // 获取令牌
        String authorization = request.getHeader("Authorization");
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            PrintWriter out = response.getWriter();
            out.write("{\"code\":401,\"msg\":\"未登录，请先登录\",\"data\":null}");
            out.flush();
            out.close();
            return false;
        }

        // 提取令牌
        String token = authorization.substring(7);
        String userId = jwtUtil.getUserIdFromToken(token);
        if (userId == null) {
            PrintWriter out = response.getWriter();
            out.write("{\"code\":401,\"msg\":\"令牌无效或过期\",\"data\":null}");
            out.flush();
            out.close();
            return false;
        }

        // 校验用户
        SysUser user = sysUserService.getUserById(userId);
        if (user == null || user.getStatus() != 1) {
            PrintWriter out = response.getWriter();
            out.write("{\"code\":403,\"msg\":\"账号不存在或已禁用\",\"data\":null}");
            out.flush();
            out.close();
            return false;
        }

        // 存入请求属性
        request.setAttribute("userId", userId);
        request.setAttribute("role", jwtUtil.getRoleFromToken(token));
        return true;
    }
}
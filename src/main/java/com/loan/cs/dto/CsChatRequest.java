package com.loan.cs.dto;

import lombok.Data;

/**
 * App → Java → Python 的对话请求。
 *
 * <p><b>注意：没有 userId 字段</b>。用户身份一律从
 * {@code request.getAttribute("userId")} 取（由 {@code JwtAuthenticationFilter} 注入），
 * 不允许客户端指定，防横向越权。
 */
@Data
public class CsChatRequest {

    /** 会话 ID，用于多轮记忆（thread_id）。为空则后端生成一个 */
    private String conversationId;

    /** 用户问题 */
    private String message;
}

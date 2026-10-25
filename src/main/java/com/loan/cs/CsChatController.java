package com.loan.cs;

import com.loan.cs.dto.CsChatRequest;
import com.loan.cs.dto.CsChatResponse;
import com.loan.entity.vo.Result;
import com.loan.exception.BusinessException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.util.UUID;

/**
 * App 端 AI 客服接口。
 *
 * <p>App 只认 Java，不直连 Python 服务——这样 JWT 鉴权、限流、审计都留在 Java 侧，
 * Python 服务可以被安全地限制在内网 / 127.0.0.1。
 */
@RestController
@RequestMapping("/api/v1/cs")
@Tag(name = "AI 客服模块", description = "对话（流式/非流式），由 Python AI 服务提供能力")
public class CsChatController {

    @Resource
    private CsChatClient csChatClient;

    /**
     * 非流式问答。
     *
     * <p>用在不支持 SSE 的场景（如 Web 端旧浏览器、或调试用 Swagger）。
     */
    @PostMapping("/chat")
    @Operation(summary = "AI 客服问答（非流式）")
    public Result<CsChatResponse> chat(@RequestBody CsChatRequest req, HttpServletRequest request) {
        String userId = currentUserId(request);
        String conversationId = resolveConversationId(req);
        String message = requireMessage(req);

        return Result.success(csChatClient.chat(userId, conversationId, message));
    }

    /**
     * 流式问答（SSE）。
     *
     * <p>Python 侧的 {@code event: delta / tool / replace / done / error} 原样透传，
     * App 端用 OkHttp SSE 解析。
     *
     * <p>注意 HTTP 语义：一旦响应体开始写，就无法再改状态码。所以 AI 服务的失败
     * 会以 {@code event: error} 的形式出现在流里，而不是 500。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "AI 客服问答（SSE 流式）")
    public ResponseEntity<StreamingResponseBody> stream(@RequestBody CsChatRequest req,
                                                        HttpServletRequest request) {
        String userId = currentUserId(request);
        String conversationId = resolveConversationId(req);
        String message = requireMessage(req);

        StreamingResponseBody body = outputStream ->
                csChatClient.stream(userId, conversationId, message, outputStream);

        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_EVENT_STREAM)
                .header("Cache-Control", "no-cache")
                .header("X-Accel-Buffering", "no")
                .body(body);
    }

    // ============================ 私有方法 ============================

    /** 用户身份只从 JWT 过滤器写入的 attribute 取，绝不接受请求体传入。 */
    private String currentUserId(HttpServletRequest request) {
        String userId = (String) request.getAttribute("userId");
        if (userId == null || userId.isBlank()) {
            throw new BusinessException(401, "未登录或登录已过期");
        }
        return userId;
    }

    private String resolveConversationId(CsChatRequest req) {
        String conv = req.getConversationId();
        if (conv == null || conv.isBlank()) {
            return UUID.randomUUID().toString();
        }
        return conv.trim();
    }

    private String requireMessage(CsChatRequest req) {
        String msg = req.getMessage();
        if (msg == null || msg.isBlank()) {
            throw new BusinessException(400, "问题不能为空");
        }
        if (msg.length() > 2000) {
            throw new BusinessException(400, "问题过长，请精简后再发");
        }
        return msg.trim();
    }
}

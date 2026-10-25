package com.loan.cs;

import com.loan.cs.dto.CsChatResponse;
import com.loan.exception.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 调 Python AI 客服服务。
 *
 * <p>用 Spring Framework 7 自带的 {@link RestClient}（在 {@code spring-web} 里），
 * <b>不需要新增任何依赖</b>——不用 WebClient（要引 webflux），也不用 OkHttp（后端没引）。
 *
 * <p>两种模式：
 * <ul>
 *   <li>{@link #chat} 非流式，返回完整回答</li>
 *   <li>{@link #stream} 流式，把 Python 的 SSE 字节<b>原样</b>透传给 App</li>
 * </ul>
 */
@Component
public class CsChatClient {

    private static final Logger log = LoggerFactory.getLogger(CsChatClient.class);

    private final RestClient restClient;

    public CsChatClient(CsAiProperties props) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(props.getConnectTimeoutMs());
        factory.setReadTimeout(props.getReadTimeoutMs());

        this.restClient = RestClient.builder()
                .baseUrl(props.getBaseUrl())
                .requestFactory(factory)
                .build();
    }

    /**
     * 非流式问答。
     *
     * @param userId         由 Java 鉴权后透传，Python 侧据此取数
     * @param conversationId 会话 ID（多轮记忆）
     * @param message        用户问题
     */
    public CsChatResponse chat(String userId, String conversationId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("userId", userId);
        payload.put("message", message);

        try {
            CsChatResponse resp = restClient.post()
                    .uri("/chat")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(payload)
                    .retrieve()
                    .body(CsChatResponse.class);

            if (resp == null) {
                throw new BusinessException(502, "AI 客服返回为空");
            }
            return resp;
        } catch (BusinessException e) {
            throw e;
        } catch (Exception e) {
            log.error("调用 AI 客服失败: userId={}, err={}", userId, e.getMessage());
            throw new BusinessException(503, "AI 客服暂时不可用，请稍后重试或转人工");
        }
    }

    /**
     * 流式问答：把 Python 的 {@code text/event-stream} 原样写入 {@code out}。
     *
     * <p>为什么原样透传而不在 Java 侧解析：SSE 的 {@code event:} 名（delta/tool/replace/done/error）
     * 是 Python 与 App 之间的契约，Java 只做通道，避免三处各维护一份解析逻辑。
     */
    public void stream(String userId, String conversationId, String message, OutputStream out) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("conversationId", conversationId);
        payload.put("userId", userId);
        payload.put("message", message);

        try {
            restClient.post()
                    .uri("/chat/stream")
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.TEXT_EVENT_STREAM)
                    .body(payload)
                    .exchange((request, response) -> {
                        try (InputStream in = response.getBody()) {
                            in.transferTo(out);
                            out.flush();
                        }
                        return null;
                    });
        } catch (Exception e) {
            log.error("转流式 AI 回答失败: userId={}, err={}", userId, e.getMessage());
            // 流已开始则无法再改 HTTP 状态码，只能推一个 error 事件让前端收尾
            try {
                String msg = "{\"message\":\"AI 客服暂时不可用，请稍后重试或转人工\"}";
                out.write(("event: error\ndata: " + msg + "\n\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception ignored) {
                // 客户端已断开，忽略
            }
        }
    }
}

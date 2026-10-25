package com.loan.cs.dto;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Python AI 服务的回答（非流式）。
 *
 * <p>字段与 Python 侧 {@code ChatResponse} 一一对应。
 */
@Data
public class CsChatResponse {

    /** 回答正文（纯文本，App 按 pre-wrap 渲染） */
    private String answer;

    /**
     * 护栏标记。
     * <ul>
     *   <li>{@code input_blocked} —— 输入命中禁止承诺词，未进模型</li>
     *   <li>{@code unsourced_number} / {@code needs_human} —— 回答里有无法溯源的数字，已被替换并建议转人工</li>
     *   <li>{@code llm_not_configured} / {@code error} —— 服务异常</li>
     * </ul>
     */
    private List<String> flags = new ArrayList<>();

    /** 本次调用到的工具名，便于排查与埋点 */
    private List<String> tools = new ArrayList<>();

    /** 是否被拦截（前端可据此隐藏"继续追问"引导） */
    private boolean blocked;
}

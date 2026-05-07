package io.agentscope.demo.app.web.dto;

import java.time.Instant;
import java.util.Map;

/**
 * 文本对话响应：助手自然语言 + 可选表单局部更新 + 服务端时间戳。
 *
 * @param reply 展示在对话气泡中的主文案
 * @param formPatch 若模型给出结构化补丁则非 null；键为表单 camelCase 字段名，值为 JSON 可序列化类型
 * @param serverTime 生成响应时的 UTC 时间，便于客户端对时或排障
 */
public record ChatResponse(
        String reply,
        Map<String, Object> formPatch,
        Instant serverTime) {}

package com.hragent.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hragent.tool.ToolRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全项目唯一"调大模型"入口（相当于 Python 版的 shared/llm.py）。
 * 铁律：所有 agent 都用这里的 callJson / call，不自己另写调模型的代码。
 *
 * 运行前在环境变量里配好：DEEPSEEK_API_KEY
 */
public class DeepSeekClient {

    private static final String API_URL = System.getenv().getOrDefault(
            "DEEPSEEK_URL", "https://api.deepseek.com/chat/completions");
    private static final String API_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    private static final String MODEL = System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat");

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private final HttpClient http = HttpClient.newHttpClient();

    /** 返回模型输出的原始字符串。 */
    public String call(String rolePrompt, String userInput) {
        return post(rolePrompt, userInput, false);
    }

    /** 要求模型输出 JSON，并解析成 Map 返回。 */
    public Map<String, Object> callJson(String rolePrompt, String userInput) {
        String text = post(rolePrompt, userInput, true);
        try {
            return MAPPER.readValue(text, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("解析模型 JSON 失败：" + text, e);
        }
    }

    private String post(String rolePrompt, String userInput, boolean jsonMode) {
        if (API_KEY.isEmpty()) {
            throw new IllegalStateException("没有配 DEEPSEEK_API_KEY，请在环境变量里设置后再运行。");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", rolePrompt));
        messages.add(Map.of("role", "user", "content", userInput));
        body.put("messages", messages);
        body.put("temperature", 0.2);
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(API_URL))
                    .header("Authorization", "Bearer " + API_KEY)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("API 错误 " + resp.statusCode() + "：" + resp.body());
            }
            Map<String, Object> root = MAPPER.readValue(resp.body(), new TypeReference<>() {});
            List<?> choices = (List<?>) root.get("choices");
            Map<?, ?> first = (Map<?, ?>) choices.get(0);
            Map<?, ?> message = (Map<?, ?>) first.get("message");
            return (String) message.get("content");
        } catch (Exception e) {
            throw new RuntimeException("调用模型失败", e);
        }
    }

    /** 进阶：让模型通过 function-calling 自己决定调用哪些工具，循环执行到给出最终回答。 */
    public Map<String, Object> runWithToolsJson(String rolePrompt, String userInput, ToolRegistry registry) {
        String content = runWithTools(rolePrompt, userInput, registry);
        try {
            return MAPPER.readValue(content, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            throw new RuntimeException("工具循环后模型未返回合法 JSON：" + content, e);
        }
    }

    public String runWithTools(String rolePrompt, String userInput, ToolRegistry registry) {
        if (API_KEY.isEmpty()) {
            throw new IllegalStateException("没有配 DEEPSEEK_API_KEY，请在环境变量里设置后再运行。");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(mapOf("role", "system", "content", rolePrompt));
        messages.add(mapOf("role", "user", "content", userInput));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", MODEL);
        body.put("temperature", 0.2);
        body.put("tools", registry.definitions());
        try {
            for (int i = 0; i < 10; i++) {
                body.put("messages", messages);
                Map<String, Object> message = sendMessage(body);
                Object content = message.get("content");
                List<?> toolCalls = (List<?>) message.get("tool_calls");
                if (toolCalls == null || toolCalls.isEmpty()) {
                    return content == null ? "" : content.toString();
                }
                // 把带 tool_calls 的 assistant 消息加回对话，再逐个执行工具
                messages.add(message);
                for (Object tco : toolCalls) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> tc = (Map<String, Object>) tco;
                    @SuppressWarnings("unchecked")
                    Map<String, Object> fn = (Map<String, Object>) tc.get("function");
                    String name = String.valueOf(fn.get("name"));
                    String argsStr = String.valueOf(fn.get("arguments"));
                    Map<String, Object> args = MAPPER.readValue(argsStr,
                            new TypeReference<Map<String, Object>>() {});
                    Map<String, Object> toolRes = registry.call(name, args);
                    Map<String, Object> toolMsg = new LinkedHashMap<>();
                    toolMsg.put("role", "tool");
                    toolMsg.put("tool_call_id", String.valueOf(tc.get("id")));
                    toolMsg.put("content", MAPPER.writeValueAsString(toolRes));
                    messages.add(toolMsg);
                }
            }
            throw new RuntimeException("工具调用次数超限");
        } catch (Exception e) {
            throw new RuntimeException("工具调用失败", e);
        }
    }

    private Map<String, Object> sendMessage(Map<String, Object> body) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("API 错误 " + resp.statusCode() + "：" + resp.body());
        }
        Map<String, Object> root = MAPPER.readValue(resp.body(), new TypeReference<>() {});
        List<?> choices = (List<?>) root.get("choices");
        Map<?, ?> first = (Map<?, ?>) choices.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        return message;
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

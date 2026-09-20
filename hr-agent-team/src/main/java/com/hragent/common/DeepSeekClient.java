package com.hragent.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hragent.tool.ToolRegistry;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 全项目唯一"调大模型"入口（相当于 Python 版的 shared/llm.py）。
 * 铁律：所有 agent 都用这里的 callJson / call，不自己另写调模型的代码。
 *
 * 模型配置有两种来源（前端「设置」面板优先，环境变量兜底）：
 *   1) 前端页面右上角「设置」填写的 API Key / 接口地址 / 模型名，随请求头传入，仅本次请求有效；
 *   2) 环境变量 DEEPSEEK_API_KEY、DEEPSEEK_URL、DEEPSEEK_MODEL。
 * 只要是 OpenAI 兼容接口（支持 response_format = json_object）的模型都可以用，不限于 DeepSeek。
 */
public class DeepSeekClient {

    /** 环境变量兜底配置（前端「设置」面板留空时使用）。 */
    private static final String ENV_URL = System.getenv().getOrDefault(
            "DEEPSEEK_URL", "https://api.deepseek.com/chat/completions");
    private static final String ENV_KEY = System.getenv().getOrDefault("DEEPSEEK_API_KEY", "");
    private static final String ENV_MODEL = System.getenv().getOrDefault("DEEPSEEK_MODEL", "deepseek-chat");

    /**
     * 前端「设置」面板传来的本次请求覆盖值。
     * 后端各阶段接口都是同步执行，一个 HTTP 请求对应一个线程，因此用 ThreadLocal 承载即可；
     * 请求结束由 Controller 调用 clearOverrides() 清理，避免线程复用污染。
     */
    private static final ThreadLocal<String> OVERRIDE_KEY = new ThreadLocal<>();
    private static final ThreadLocal<String> OVERRIDE_URL = new ThreadLocal<>();
    private static final ThreadLocal<String> OVERRIDE_MODEL = new ThreadLocal<>();

    /** 由 Controller 在每个请求开始时写入（可为 null，表示该字段沿用环境变量）。 */
    public static void setOverrides(String key, String baseUrl, String model) {
        OVERRIDE_KEY.set(blankToNull(key));
        OVERRIDE_URL.set(normalizeUrl(baseUrl));
        OVERRIDE_MODEL.set(blankToNull(model));
    }

    /** 请求结束务必清理。 */
    public static void clearOverrides() {
        OVERRIDE_KEY.remove();
        OVERRIDE_URL.remove();
        OVERRIDE_MODEL.remove();
    }

    /**
     * 本次请求的模型用量（token 与耗时）。
     * 放在 ThreadLocal 里而不是实例字段，是因为前端会并发跑多个候选人，
     * 一个 DeepSeekClient 实例可能同时被两条请求线程使用，用实例字段会串数。
     */
    public static final class Usage {
        private int calls;
        private long promptTokens;
        private long completionTokens;
        private long totalTokens;
        private long modelMs;

        public synchronized void add(int prompt, int completion, int total, long ms) {
            calls++;
            promptTokens += Math.max(0, prompt);
            completionTokens += Math.max(0, completion);
            totalTokens += Math.max(0, total);
            modelMs += Math.max(0, ms);
        }

        public int calls() {
            return calls;
        }

        public long promptTokens() {
            return promptTokens;
        }

        public long completionTokens() {
            return completionTokens;
        }

        public long totalTokens() {
            return totalTokens;
        }

        public long modelMs() {
            return modelMs;
        }

        /** 生成速度（输出 token / 秒）：模型侧真正花的时间，不含排队与网络往返 */
        public int tokensPerSecond() {
            if (modelMs <= 0) return 0;
            return (int) Math.round(completionTokens * 1000.0 / modelMs);
        }

        public Map<String, Object> toMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("calls", calls);
            m.put("promptTokens", promptTokens);
            m.put("completionTokens", completionTokens);
            m.put("totalTokens", totalTokens);
            m.put("modelMs", modelMs);
            m.put("tokensPerSecond", tokensPerSecond());
            return m;
        }
    }

    private static final ThreadLocal<Usage> USAGE = ThreadLocal.withInitial(Usage::new);

    /** 每个请求开始时归零（由 Controller 调用）。 */
    public static void resetUsage() {
        USAGE.set(new Usage());
    }

    /** 取走本次请求累计的用量（不清零，方便同一请求里多次读取）。 */
    public static Usage currentUsage() {
        return USAGE.get();
    }

    /** 请求结束时清理，避免线程复用污染。 */
    public static void clearUsage() {
        USAGE.remove();
    }

    private static String blankToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 前端填「接口地址」时可能只填域名，这里统一补成 /chat/completions 端点。
     * 例：https://api.deepseek.com      -> https://api.deepseek.com/chat/completions
     *     https://api.openai.com/v1     -> https://api.openai.com/v1/chat/completions
     *     已经带 /chat/completions 的则原样使用
     */
    private static String normalizeUrl(String base) {
        String b = blankToNull(base);
        if (b == null) {
            return null;
        }
        while (b.endsWith("/")) {
            b = b.substring(0, b.length() - 1);
        }
        if (b.endsWith("/chat/completions")) {
            return b;
        }
        return b + "/chat/completions";
    }

    private static String apiKey() {
        String k = OVERRIDE_KEY.get();
        return k != null ? k : ENV_KEY;
    }

    private static String apiUrl() {
        String u = OVERRIDE_URL.get();
        return u != null ? u : ENV_URL;
    }

    private static String model() {
        String m = OVERRIDE_MODEL.get();
        return m != null ? m : ENV_MODEL;
    }

    /** 当前生效的模型名（供「测试连接」回显）；需在 setOverrides 之后调用 */
    public static String activeModel() {
        return model();
    }

    /** 当前生效的接口地址 */
    public static String activeUrl() {
        return apiUrl();
    }

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
        if (apiKey().isEmpty()) {
            throw new IllegalStateException(
                    "没有配置 API Key：请在页面右上角「设置」里填写，或先设置环境变量 DEEPSEEK_API_KEY。");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", rolePrompt));
        messages.add(Map.of("role", "user", "content", userInput));
        body.put("messages", messages);
        body.put("temperature", 0.2);
        body.put("max_tokens", 8192); // 长问答时避免输出被截断，导致 JSON 解析失败
        if (jsonMode) {
            body.put("response_format", Map.of("type", "json_object"));
        }
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(apiUrl()))
                    .header("Authorization", "Bearer " + apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                    .timeout(Duration.ofSeconds(300))
                    .build();
            long t0 = System.currentTimeMillis();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            long ms = System.currentTimeMillis() - t0;
            if (resp.statusCode() / 100 != 2) {
                throw new RuntimeException("API 错误 " + resp.statusCode() + "：" + resp.body());
            }
            Map<String, Object> root = MAPPER.readValue(resp.body(), new TypeReference<>() {});
            recordUsage(root, ms); // token 与耗时同样要记账（A/C/D/E 都走这条路径）
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
        if (apiKey().isEmpty()) {
            throw new IllegalStateException(
                    "没有配置 API Key：请在页面右上角「设置」里填写，或先设置环境变量 DEEPSEEK_API_KEY。");
        }
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(mapOf("role", "system", "content", rolePrompt));
        messages.add(mapOf("role", "user", "content", userInput));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model());
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
                    System.out.println("[工具调用] " + name + "(" + argsStr + ")");
                    Map<String, Object> toolRes = registry.call(name, args);
                    System.out.println("[工具结果] " + name + " -> " + MAPPER.writeValueAsString(toolRes));
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
                .uri(URI.create(apiUrl()))
                .header("Authorization", "Bearer " + apiKey())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(body)))
                .timeout(Duration.ofSeconds(180))
                .build();
        long t0 = System.currentTimeMillis();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        long ms = System.currentTimeMillis() - t0;
        if (resp.statusCode() / 100 != 2) {
            throw new RuntimeException("API 错误 " + resp.statusCode() + "：" + resp.body());
        }
        Map<String, Object> root = MAPPER.readValue(resp.body(), new TypeReference<>() {});
        recordUsage(root, ms);
        List<?> choices = (List<?>) root.get("choices");
        Map<?, ?> first = (Map<?, ?>) choices.get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> message = (Map<String, Object>) first.get("message");
        return message;
    }

    /**
     * 记下这一轮的 token 用量与耗时。
     * 用量取自响应里的 usage 字段（OpenAI 兼容接口都有）；有些兼容服务不返回，
     * 那就只记耗时，token 保持 0，前端会退化成只显示用时。
     */
    @SuppressWarnings("unchecked")
    private static void recordUsage(Map<String, Object> root, long ms) {
        int prompt = 0;
        int completion = 0;
        int total = 0;
        if (root.get("usage") instanceof Map<?, ?> u) {
            prompt = intOf(u.get("prompt_tokens"));
            completion = intOf(u.get("completion_tokens"));
            total = intOf(u.get("total_tokens"));
            if (total == 0) total = prompt + completion;
        }
        USAGE.get().add(prompt, completion, total, ms);
    }

    /** usage 里的数字可能是 Integer / Long / String，统一取值 */
    private static int intOf(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o == null) return 0;
        try {
            return Integer.parseInt(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            return 0;
        }
    }

    private static Map<String, Object> mapOf(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }
}

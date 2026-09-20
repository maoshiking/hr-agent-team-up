package com.hragent.tool;

import java.util.List;
import java.util.Map;

/**
 * 面向 agent 的工具门面（简单版）。
 * 不想走 function-calling 循环时，agent 可直接用这里的静态方法"真调用工具、落成文件"：
 *
 *   Map ok = AgentTools.writeDoc("csv", "score.csv", "姓名,分数\\n张三,85");
 *   Map parsed = AgentTools.parseResume(resumeText);
 *
 * 进阶（模型自己决定调哪个工具）：用 DeepSeekClient.runWithTools(role, user, AgentTools.registry())。
 */
public final class AgentTools {

    private static final ToolRegistry REGISTRY = new ToolRegistry();

    static {
        REGISTRY.register(new DocWriterTool());
        REGISTRY.register(new ResumeParserTool());
    }

    private AgentTools() {
    }

    /** 拿全局注册表（doc_writer + resume_parser）。 */
    public static ToolRegistry registry() {
        return REGISTRY;
    }

    /** 把内容写成本地文件（csv/html/md/txt）。 */
    public static Map<String, Object> writeDoc(String format, String filename, String content) throws Exception {
        return REGISTRY.call("doc_writer",
                Map.of("format", format, "filename", filename, "content", content));
    }

    /** 解析简历文本。 */
    public static Map<String, Object> parseResume(String text) throws Exception {
        return REGISTRY.call("resume_parser", Map.of("text", text));
    }

    /**
     * 从任意输入里取候选人姓名，生成可直接拼进文件名的安全后缀。
     * 多候选人时用来区分「面试纪要-张三.html / 面试纪要-李四.html」，避免互相覆盖。
     *
     * @return 形如 "-张三"；取不到姓名或姓名非法时返回 ""（不带后缀）
     */
    public static String nameSuffix(Object resumeOrName) {
        String name = extractName(resumeOrName);
        if (name.isBlank()) return "";
        String safe = safeName(name);
        return safe.isBlank() ? "" : "-" + safe;
    }

    /** 文件名里不能出现路径分隔符等字符 */
    public static String safeName(String name) {
        return String.valueOf(name == null ? "" : name)
                .trim()
                .replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
    }

    /** 兼容三种传入：直接给姓名、给 resume Map、给 candidate Map */
    @SuppressWarnings("unchecked")
    private static String extractName(Object o) {
        if (o == null) return "";
        if (o instanceof String s) return s;
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> map = (Map<String, Object>) m;
            Object name = map.get("name");
            if (name != null) return String.valueOf(name);
            // 传进来的是 candidate{candidate_id, name} 之外的壳时，再往里找一层
            Object nested = map.get("resume");
            if (nested instanceof Map<?, ?>) return extractName(nested);
        }
        return "";
    }
}

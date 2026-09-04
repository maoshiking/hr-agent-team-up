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

    /** 把结果 JSON 简单渲染成 Markdown（便于 doc_writer 落成报告/网页文件）。 */
    public static String toMarkdown(String title, Map<String, Object> json) {
        StringBuilder sb = new StringBuilder();
        sb.append("# ").append(title == null ? "成果" : title).append("\n\n");
        writeMap(sb, json, 1);
        return sb.toString();
    }

    private static void writeMap(StringBuilder sb, Map<String, Object> m, int level) {
        for (Map.Entry<String, Object> e : m.entrySet()) {
            Object val = e.getValue();
            String indent = "  ".repeat(level);
            if (val instanceof Map) {
                sb.append(indent).append("## ").append(e.getKey()).append("\n");
                @SuppressWarnings("unchecked")
                Map<String, Object> sub = (Map<String, Object>) val;
                writeMap(sb, sub, level + 1);
            } else if (val instanceof List) {
                sb.append(indent).append("- **").append(e.getKey()).append("**\n");
                int i = 1;
                for (Object item : (List<?>) val) {
                    if (item instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> m2 = (Map<String, Object>) item;
                        sb.append(indent).append("  ").append(i++).append(".\n");
                        writeMap(sb, m2, level + 1);
                    } else {
                        sb.append(indent).append("  - ").append(item).append("\n");
                    }
                }
            } else {
                sb.append(indent).append("- **").append(e.getKey()).append("**: ").append(val).append("\n");
            }
        }
    }
}

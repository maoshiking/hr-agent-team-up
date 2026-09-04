package com.hragent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：doc_writer —— 把内容写成真实文件（.csv / .html / .md / .txt）。
 * 这是"真行动 + 可验收成果"的关键：agent 调用它，就把结果落成本地文件。
 *
 * 参数：{ format: csv|html|md|txt, filename: 文件名, content: 内容 }
 * 输出目录：环境变量 HR_DEMO_DIR 指定的目录，缺省用 "demo"。
 */
public class DocWriterTool implements Tool {

    @Override
    public String name() {
        return "doc_writer";
    }

    @Override
    public String description() {
        return "把一段文本内容写成本地文件（csv/html/md/txt），用于把成果落成可交付文件。"
                + "当需要产出一份报告/表格/网页文件时调用它。参数：format、filename、content。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("format", Map.of("type", "string", "enum",
                new String[]{"csv", "html", "md", "txt"}, "description", "文件格式"));
        properties.put("filename", Map.of("type", "string", "description", "文件名，如 score.csv"));
        properties.put("content", Map.of("type", "string", "description", "要写入文件的完整内容"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", new String[]{"format", "filename", "content"});
        return schema;
    }

    @Override
    public Map<String, Object> run(Map<String, Object> args) throws Exception {
        String format = String.valueOf(args.getOrDefault("format", "txt")).toLowerCase();
        String filename = String.valueOf(args.getOrDefault("filename", "out.txt"));
        String content = String.valueOf(args.getOrDefault("content", ""));

        String base = System.getenv("HR_DEMO_DIR");
        if (base == null || base.isBlank()) {
            base = "demo";
        }
        // 若调用者没带扩展名，按 format 补
        String lowerName = filename.toLowerCase();
        boolean hasExt = lowerName.endsWith(".csv") || lowerName.endsWith(".html")
                || lowerName.endsWith(".md") || lowerName.endsWith(".txt");
        if (!hasExt && !format.isBlank()) {
            filename = filename + "." + format;
        }
        Path dir = Paths.get(base);
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", file.toAbsolutePath().toString());
        result.put("filename", filename);
        return result;
    }
}

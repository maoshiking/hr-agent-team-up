package com.hragent.tool;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
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

        // 若调用者没带扩展名，按 format 补
        String lowerName = filename.toLowerCase();
        boolean hasExt = lowerName.endsWith(".csv") || lowerName.endsWith(".html")
                || lowerName.endsWith(".md") || lowerName.endsWith(".txt");
        if (!hasExt && !format.isBlank()) {
            filename = filename + "." + format;
        }
        Path dir = baseDir();
        Files.createDirectories(dir);
        Path file = dir.resolve(filename);
        Files.write(file, content.getBytes(StandardCharsets.UTF_8));

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("path", file.toAbsolutePath().toString());
        result.put("filename", filename);
        return result;
    }

    /**
     * 成果文件输出目录（**唯一口径**，读写都用它）：
     * 环境变量 {@code HR_DEMO_DIR} 优先，否则用项目下的 {@code out/}。
     *
     * <p>两件事是这次特意改的：
     * <ol>
     *   <li>以前 {@code demo} 这个默认值在**两个地方各写了一遍**（这里和文件下载接口），
     *       改一处漏一处就会"写进去了但下载 404"；现在只剩这一处。</li>
     *   <li>默认目录从 {@code demo} 改成 {@code out}：{@code demo/} 里放的是**手写的演示样例**，
     *       每次跑流程都往里吐几十个 HTML 会把样例淹掉（上次整理时就发现里面堆了一百多个历次产物）。</li>
     * </ol>
     */
    public static Path baseDir() {
        String base = System.getenv("HR_DEMO_DIR");
        if (base == null || base.isBlank()) {
            base = "out";
        }
        return Paths.get(base).toAbsolutePath();
    }
}

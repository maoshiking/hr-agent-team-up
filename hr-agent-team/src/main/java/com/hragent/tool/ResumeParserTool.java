package com.hragent.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具：resume_parser —— 把简历文本做基础结构化抽取（尽力而为）。
 * 作用：让 agent 真正"调用一个解析工具"而不是只靠模型硬读；语义判断仍可交给模型。
 */
public class ResumeParserTool implements Tool {

    private static final Pattern YEARS_CN = Pattern.compile("(\\d{1,2})\\s*年(?:以上)?(?:的)?.*经验");
    private static final Pattern YEARS_EN = Pattern.compile("(\\d{1,2})\\s*(?:\\+\\s*)?years?");

    @Override
    public String name() {
        return "resume_parser";
    }

    @Override
    public String description() {
        return "把一份简历文本做基础结构化抽取，返回姓名/年限等尽力而为的字段。"
                + "当需要解析候选人简历文本时调用它。参数：text。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("text", Map.of("type", "string", "description", "简历原文"));
        schema.put("properties", props);
        schema.put("required", new String[]{"text"});
        return schema;
    }

    @Override
    public Map<String, Object> run(Map<String, Object> args) {
        String text = String.valueOf(args.getOrDefault("text", ""));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("inputLength", text.length());
        result.put("preview", text.length() > 200 ? text.substring(0, 200) : text);
        result.put("name", guessName(text));
        result.put("years", guessYears(text));
        List<String> skillHits = new ArrayList<>();
        for (String s : new String[]{"java", "python", "sql", "数据分析", "产品", "运营", "销售", "人力资源"}) {
            if (text.toLowerCase().contains(s)) {
                skillHits.add(s);
            }
        }
        result.put("skillHits", skillHits);
        result.put("note", "尽力而为的基础抽取；语义判断请交给模型。");
        return result;
    }

    private String guessName(String text) {
        String[] lines = text.split("\\r?\\n");
        for (String line : lines) {
            String t = line.trim();
            if (!t.isEmpty() && t.length() <= 8 && !t.matches(".*\\d.*")) {
                return t;
            }
        }
        return "";
    }

    private Integer guessYears(String text) {
        Matcher m = YEARS_CN.matcher(text);
        if (m.find()) {
            return Integer.valueOf(m.group(1));
        }
        m = YEARS_EN.matcher(text);
        if (m.find()) {
            return Integer.valueOf(m.group(1));
        }
        return null;
    }
}

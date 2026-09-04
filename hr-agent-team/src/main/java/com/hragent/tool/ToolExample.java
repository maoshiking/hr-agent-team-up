package com.hragent.tool;

import java.util.Map;

/**
 * 工具调用示例（怎么看怎么用）。
 *
 * 用法一（简单）：直接调用 AgentTools 真落文件。
 *   Map r = AgentTools.writeDoc("csv", "score.csv", "姓名,分数\n张三,85");
 *   System.out.println(r.get("path"));
 *
 * 用法二（进阶，function-calling）：让模型自己决定调哪个工具。
 *   DeepSeekClient client = new DeepSeekClient();
 *   Map out = client.runWithToolsJson(ROLE, userInput, AgentTools.registry());
 */
public class ToolExample {

    public static void main(String[] args) throws Exception {
        // 演示 doc_writer：把成果写成文件（输出到 HR_DEMO_DIR 或默认 demo/）
        Map<String, Object> ok = AgentTools.writeDoc(
                "csv", "example_score.csv", "姓名,分数\n张三,85\n李四,72");
        System.out.println("写出文件：" + ok.get("path"));

        // 演示 resume_parser：解析一段简历文本
        Map<String, Object> parsed = AgentTools.parseResume(
                "张三\n5年数据分析经验\n技能：python、sql、数据分析");
        System.out.println("解析简历 years=" + parsed.get("years")
                + " skillHits=" + parsed.get("skillHits"));
    }
}

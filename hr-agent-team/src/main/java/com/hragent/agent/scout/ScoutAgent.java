package com.hragent.agent.scout;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历猎手（成员 B 专属）· 技能 resume.screen · 文件 agent/scout/ScoutAgent.java
 *
 * 职责：拿 A 的 JD，读候选人简历 → 结构化 → 按 JD 打分。上游 A，下游 C(面试官)。
 *
 * 开发说明：run() 已接好 读输入 → 🔧 resume_parser 解析 → 调模型 → 🔧 doc_writer 落表格 → 返回 JSON。
 */
public class ScoutAgent implements Agent {

    // TODO(成员 B)：按你的判断完善这段 ROLE。
    private static final String ROLE =
            "你是简历猎手，招聘流程第二步的数字员工。\n" +
            "职责：根据 JD 对候选人简历做匹配打分。\n" +
            "输入字段：jd（结构化岗位）、resume（简历文本或结构化）、可加 resumeParsed。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - score: 0-100 的整数\n" +
            "  - verdict: 只能 shortlist / hold / reject\n" +
            "  - matched: [{skill, evidence}] 命中的技能与证据\n" +
            "  - gaps: [] 缺口\n" +
            "  - reason\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造证据，不确定就 verdict=hold 并说明。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            // 0) 🔧 真调用工具 resume_parser：若 resume 是文本则先解析成结构化
            Object resume = payload.get("resume");
            if (resume != null && resume instanceof String) {
                Map<String, Object> parsed = AgentTools.parseResume(resume.toString());
                payload = new LinkedHashMap<>(payload);
                payload.put("resumeParsed", parsed);
            }

            // 1) 调模型打分
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 2) 🔧 真调用工具 doc_writer：把打分落成打分表（表格）
            AgentTools.writeDoc("csv", "scout_score.csv",
                    "score,verdict,reason\n"
                    + result.get("score") + "," + result.get("verdict") + "," + result.get("reason"));

            // 3) TODO(成员 B)：校验 verdict 枚举合法、score 在 0-100。
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ScoutAgent 执行失败", e);
        }
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        ScoutAgent agent = new ScoutAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jd", Map.of("title", "数据分析师",
                "hard_requirements", new String[]{"Python", "SQL", "数据分析经验"}));
        sample.put("resume", "张三\n5年数据分析经验\n技能：python、sql、报表搭建");
        System.out.println(agent.run(sample));
    }
}

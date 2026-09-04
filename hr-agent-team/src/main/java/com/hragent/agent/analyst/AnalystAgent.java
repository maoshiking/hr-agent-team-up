package com.hragent.agent.analyst;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 招聘分析师（成员 A 专属）· 技能 talent.analyze · 文件 agent/analyst/AnalystAgent.java
 *
 * 职责：把模糊招聘诉求 → 需求澄清 + 结构化 JD + 候选人能力画像。无上游，下游 B(简历猎手)。
 *
 * 开发说明（成员 A 只需在此基础上补/调 ROLE 文案与校验）：
 *  run() 已接好：读输入 → 调模型(callJson) → 🔧 真调用 doc_writer 落成成果文件 → 返回 JSON。
 */
public class AnalystAgent implements Agent {

    // TODO(成员 A)：按你的判断完善这段 ROLE（职责、输入/输出字段、风格）。字段别自创。
    private static final String ROLE =
            "你是招聘分析师，招聘流程开头的数字员工。\n" +
            "职责：把用户模糊的招聘诉求，澄清为结构化需求，并生成结构化 JD 与候选人能力画像。\n" +
            "输入字段：raw_demand（原始诉求，可含少量上下文）。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - demand: 澄清后的需求陈述 + 待确认/追问清单\n" +
            "  - jd: { title, responsibilities[], hard_requirements[], nice_to_have[] }\n" +
            "  - persona: { core_competencies[], soft_traits[], culture_fit_hint }\n" +
            "  - reason\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造，信息不足就给出待确认清单。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;

            // 1) 调模型，要求返回符合上面字段的 JSON
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 2) 🔧 真调用工具 doc_writer：把结果落成一份成果文件（报告/网页）
            AgentTools.writeDoc("md", "analyst_result.md",
                    AgentTools.toMarkdown("招聘需求分析与 JD", result));

            // 3) TODO(成员 A)：按契约校验字段/必填项，不合格就修正或抛错。
            return result;
        } catch (Exception e) {
            throw new RuntimeException("AnalystAgent 执行失败", e);
        }
    }

    // 自测：右键运行 main（需先配好 DEEPSEEK_API_KEY）
    public static void main(String[] args) {
        AnalystAgent agent = new AnalystAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("raw_demand", "招一个会做数据分析的人，能独立建报表");
        System.out.println(agent.run(sample));
    }
}

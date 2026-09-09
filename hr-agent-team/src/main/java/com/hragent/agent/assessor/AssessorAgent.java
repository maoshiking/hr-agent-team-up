package com.hragent.agent.assessor;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 测评背调员（成员 D 专属）· 技能 assess.review · 文件 agent/assessor/AssessorAgent.java
 *
 * 职责：对候选人做 技能测评 + 真实性核查 + 文化/团队匹配。上游 C，下游 E(offer 管家)。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 🔧 doc_writer 落报告 → 返回 JSON。
 */
public class AssessorAgent implements Agent {

    // TODO(成员 D)：按你的判断完善这段 ROLE。
    private static final String ROLE =
            "你是测评背调员，招聘流程第四步的数字员工。\n" +
            "职责：对候选人做技能测评、经历真实性核查、文化/团队匹配。\n" +
            "输入字段：jd、resume、minutes（面试纪要）、可加 team_profile。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - level: 只能 expert / proficient / basic / none\n" +
            "  - score: 0-100\n" +
            "  - risk_level: 只能 low / medium / high\n" +
            "  - findings: [{item, risk, evidence}]（真实性核查发现，每项必须带 evidence）\n" +
            "  - fit_score: 0-100、fills_gap: []、overlaps: []\n" +
            "  - reason\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造证据。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 🔧 真调用 doc_writer：把测评结果落成报告文件
            AgentTools.writeDoc("md", "assessor_report.md",
                    AgentTools.toMarkdown("候选人测评报告", result));

            // TODO(成员 D)：校验 level / risk_level 枚举合法、risk 项带 evidence。
            return result;
        } catch (Exception e) {
            throw new RuntimeException("AssessorAgent 执行失败", e);
        }
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        AssessorAgent agent = new AssessorAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jd", Map.of("title", "数据分析师"));
        sample.put("resume", Map.of("name", "张三", "skills", "python,sql"));
        sample.put("minutes", Map.of("summary", "表现中上", "verdict", "shortlist"));
        System.out.println(agent.run(sample));
    }
}

package com.hragent.agent.concierge;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * offer 与入职管家（成员 E 专属）· 技能 offer.onboard · 文件 agent/concierge/ConciergeAgent.java
 *
 * 职责：拿 D 的测评结论 → 生成 offer 草案与谈薪建议 → 制定 30 天入职计划（含反哺筛选建议）。
 * 上游 D，无下游（流程收尾）。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 🔧 doc_writer 落网页 → 返回 JSON。
 */
public class ConciergeAgent implements Agent {

    // TODO(成员 E)：按你的判断完善这段 ROLE。
    private static final String ROLE =
            "你是 offer 与入职管家，招聘流程收尾的数字员工。\n" +
            "职责：基于候选人综合结论生成 offer 草案与谈薪建议，并制定 30 天入职计划。\n" +
            "输入字段：candidate_summary（测评/综合结论）、可加 salary_data（市场基准）。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - offer: { position, salary_range, suggested, clauses[] }\n" +
            "  - plan: { week1_4[], goals[] }\n" +
            "  - screening_feedback: 回写给筛选标准的改进建议\n" +
            "  - reason\n" +
            "薪资给区间并说明依据，禁止编造具体数字。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 🔧 真调用 doc_writer：把 offer/入职计划落成网页（成果文件）
            String html = "<!DOCTYPE html><html><meta charset=\"utf-8\"><body><h1>Offer 与入职计划</h1>"
                    + "<pre>" + result + "</pre></body></html>";
            AgentTools.writeDoc("html", "offer_onboarding.html", html);

            // TODO(成员 E)：校验 offer 四字段完整、薪资为区间且有依据。
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ConciergeAgent 执行失败", e);
        }
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        ConciergeAgent agent = new ConciergeAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("candidate_summary", Map.of("name", "张三", "level", "proficient"));
        sample.put("salary_data", "数据分析师一线城市约 15-25K");
        System.out.println(agent.run(sample));
    }
}

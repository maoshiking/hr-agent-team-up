package com.hragent.agent.interviewer;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试官（成员 C 专属）· 技能 interview.run · 文件 agent/interviewer/InterviewerAgent.java
 *
 * 职责：按 JD+简历生成面试题纲；根据问答记录生成纪要并给结论。上游 B，下游 D(测评背调员)。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 🔧 doc_writer 落文件 → 返回 JSON。
 * 输入含 transcript（问答记录）→ 出"纪要"；否则 → 出"题纲"。
 */
public class InterviewerAgent implements Agent {

    // TODO(成员 C)：按你的判断完善这段 ROLE。
    private static final String ROLE =
            "你是面试官，招聘流程第三步的数字员工。\n" +
            "职责：\n" +
            "  1) 若输入只有 jd/resume（无 transcript）：生成结构化面试题纲；\n" +
            "  2) 若输入含 transcript（问答记录）：生成面试纪要并给录用判定。\n" +
            "输出 JSON：题纲用 plan{ questions[]{text,intent}, rubric[]{dimension,weight} }；"
            + "纪要用 minutes{ summary, qa[]{question,answer,score}, verdict }。\n" +
            "verdict 只能 shortlist / hold / reject。\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 🔧 真调用 doc_writer：把题纲/纪要落成成果文件
            boolean hasTranscript = payload.containsKey("transcript");
            AgentTools.writeDoc("md",
                    hasTranscript ? "interview_minutes.md" : "interview_plan.md",
                    AgentTools.toMarkdown(hasTranscript ? "面试纪要" : "面试题纲", result));

            // TODO(成员 C)：校验字段（含 transcript 时应有 verdict）。
            return result;
        } catch (Exception e) {
            throw new RuntimeException("InterviewerAgent 执行失败", e);
        }
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        InterviewerAgent agent = new InterviewerAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("jd", Map.of("title", "数据分析师"));
        sample.put("resume", Map.of("name", "张三", "skills", "python,sql"));
        sample.put("transcript", "Q1: 你做过哪些报表？A1: 电商日报、活动复盘。");
        System.out.println(agent.run(sample));
    }
}

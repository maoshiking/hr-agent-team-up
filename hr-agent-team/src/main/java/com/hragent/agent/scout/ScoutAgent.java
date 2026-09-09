package com.hragent.agent.scout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历猎手（成员 B 专属）· 技能 resume.screen · 文件 agent/scout/ScoutAgent.java
 *
 * 职责：拿 A 的 JD，读候选人简历 → 结构化(resume) → 按 JD 打分。上游 A，下游 C(面试官)。
 *
 * 开发说明：
 *  - run()：单份简历 解析→打分→校验→落表，是给调度层/整合者调用的契约入口。
 *  - main()：本地演示「接 A 的 JD → 批量筛选模拟简历 → 提取合格简历」，输出带 UTF-8 BOM 的两份成果表。
 */
public class ScoutAgent implements Agent {

    // TODO(成员 B)：可继续按你的判断微调，但"输出字段"不要改（见手册第1节）。
    private static final String ROLE =
            "你是简历猎手，招聘流程第二步的数字员工。\n" +
            "你会用到的工具：resume_parser（解析简历文本为结构化）、doc_writer（把结果写成表格文件）。\n" +
            "职责：根据 JD，先把候选人简历结构化为 resume，再按 JD 做匹配打分。\n" +
            "输入字段：jd（结构化岗位）、resume（简历文本，可含 resumeParsed 初步解析）。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - resume: { name, years, skills[], experiences[], education }\n" +
            "  - score: 0-100 的整数\n" +
            "  - verdict: 只能 shortlist / hold / reject\n" +
            "  - matched: [{skill, evidence}] 命中的技能与证据\n" +
            "  - gaps: [] 缺口\n" +
            "  - reason\n" +
            "风格：先结论、后依据、再风险、再下一步；禁止编造证据，不确定就 verdict=hold 并说明。";

    private final DeepSeekClient client = new DeepSeekClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            Map<String, Object> result = screenOne(payload);
            List<Map<String, Object>> rows = new ArrayList<>();
            rows.add(result);
            writeDeliverables(rows);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ScoutAgent 执行失败", e);
        }
    }

    /** 核心：单份简历 解析→打分→规范化→校验，返回结果 Map（不写文件）。 */
    private Map<String, Object> screenOne(Map<String, Object> payload) throws Exception {
        // 0) 🔧 真调用工具 resume_parser：简历是文本则先解析
        Object resumeRaw = payload.get("resume");
        Map<String, Object> parsedBasic = new LinkedHashMap<>();
        if (resumeRaw instanceof String) {
            parsedBasic = AgentTools.parseResume(resumeRaw.toString());
            payload = new LinkedHashMap<>(payload);
            payload.put("resumeParsed", parsedBasic);
        }

        // 1) 调模型：输出 resume 结构 + 打分
        String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
        Map<String, Object> result = client.callJson(ROLE, userInput);

        // 兜底：把 resume 规范成契约字段 {name,years,skills[],experiences[],education}
        result.put("resume", normalizeResume(result.get("resume"), parsedBasic));

        // 2) 校验 verdict 枚举 / score 范围
        validateVerdict(String.valueOf(result.get("verdict")));
        validateScore(result.get("score"));
        return result;
    }

    /** 🔧 真调用 doc_writer，把结果落成两份可验收表（带 UTF-8 BOM，Excel 打开不乱码）。 */
    private void writeDeliverables(List<Map<String, Object>> rows) throws Exception {
        StringBuilder resumeSb = new StringBuilder("﻿name,years,skills,experiences,education\n");
        StringBuilder scoreSb = new StringBuilder("﻿score,verdict,matched,gaps,reason\n");
        for (Map<String, Object> r : rows) {
            @SuppressWarnings("unchecked")
            Map<String, Object> res = (Map<String, Object>) r.get("resume");
            resumeSb.append(csvVal(res.get("name"))).append(",")
                    .append(csvVal(res.get("years"))).append(",")
                    .append(csvVal(res.get("skills"))).append(",")
                    .append(csvVal(res.get("experiences"))).append(",")
                    .append(csvVal(res.get("education"))).append("\n");
            scoreSb.append(csvVal(r.get("score"))).append(",")
                    .append(csvVal(r.get("verdict"))).append(",")
                    .append(csvVal(r.get("matched"))).append(",")
                    .append(csvVal(r.get("gaps"))).append(",")
                    .append(csvVal(r.get("reason"))).append("\n");
        }
        AgentTools.writeDoc("csv", "resume_parsed.csv", resumeSb.toString());
        AgentTools.writeDoc("csv", "scout_score.csv", scoreSb.toString());
    }

    private void validateVerdict(String verdict) {
        if (verdict == null || verdict.isBlank()
                || !("shortlist".equals(verdict) || "hold".equals(verdict) || "reject".equals(verdict))) {
            throw new IllegalStateException("verdict 非法（须 shortlist/hold/reject）：" + verdict);
        }
    }

    private void validateScore(Object score) {
        if (score == null) {
            throw new IllegalStateException("缺少 score");
        }
        double d;
        try {
            d = Double.parseDouble(String.valueOf(score));
        } catch (NumberFormatException e) {
            throw new IllegalStateException("score 不是数字：" + score);
        }
        if (d < 0 || d > 100 || d != Math.floor(d)) {
            throw new IllegalStateException("score 须为 0-100 整数：" + score);
        }
    }

    /** 把 resume 规范成契约字段 {name,years,skills[],experiences[],education}；缺失/类型不对时用 parser 兜底。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalizeResume(Object resumeVal, Map<String, Object> parsedBasic) {
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> src = resumeVal instanceof Map ? (Map<String, Object>) resumeVal : null;
        out.put("name", src != null ? src.get("name") : parsedBasic.get("name"));
        out.put("years", src != null ? src.get("years") : parsedBasic.get("years"));
        Object skills = src != null ? src.get("skills") : null;
        if (skills == null && parsedBasic.get("skillHits") != null) {
            skills = parsedBasic.get("skillHits");
        }
        out.put("skills", skills);
        out.put("experiences", src != null ? src.get("experiences") : null);
        out.put("education", src != null ? src.get("education") : null);
        return out;
    }

    private static String csvVal(Object o) {
        if (o == null) {
            return "";
        }
        String s = String.valueOf(o);
        return s.replace("\r", " ").replace("\n", " ").replace(",", "；");
    }

    /** 自测/演示：接成员 A 的 JD（GBK 编码），批量筛选模拟简历，提取合格简历。 */
    public static void main(String[] args) throws Exception {
        String jdPath = args.length > 0 ? args[0] : "D:/毕业晚会节目/模拟测试A传出.json";
        String jdJson = new String(Files.readAllBytes(Path.of(jdPath)), Charset.forName("GBK"));
        Map<String, Object> jd = MAPPER.readValue(jdJson, new TypeReference<Map<String, Object>>() {});

        // 模拟候选简历（覆盖 shortlist / hold / reject 三档）
        List<String> resumes = List.of(
                "陈晨\n本科 统计学\n2年数据分析经验\n技能：SQL、Python、Tableau\n负责业务数据报表搭建与自动化，独立完成数据分析报告",
                "林悦\n本科 计算机\n3年数据运营经验\n技能：SQL、Python、PowerBI\n搭建并维护多个数据报表，熟悉自动化报表开发",
                "孙倩\n本科 数据科学\n应届毕业生，无全职经验\n技能：SQL、Python、Excel\n在校做过数据分析项目",
                "赵磊\n大专\n4年数据分析相关经验\n技能：Excel、SQL（基础）\n日常做数据整理与简单报表，未用过 Python",
                "周涛\n高中\n5年销售经验\n技能：销售、沟通\n无数据相关经验，不会 SQL 或 Python",
                "吴昊\n本科 数学\n8年数据分析经验\n技能：SQL、Python、Spark\n搭建自动化报表平台，主导多个分析项目"
        );

        ScoutAgent agent = new ScoutAgent();
        List<Map<String, Object>> results = new ArrayList<>();
        for (String resume : resumes) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jd", jd);
            payload.put("resume", resume);
            results.add(agent.screenOne(payload));
        }
        agent.writeDeliverables(results);

        // 提取合格简历（verdict=shortlist）
        System.out.println("=== 合格简历（verdict=shortlist）===");
        int n = 0;
        for (Map<String, Object> r : results) {
            if ("shortlist".equals(r.get("verdict"))) {
                n++;
                @SuppressWarnings("unchecked")
                Map<String, Object> res = (Map<String, Object>) r.get("resume");
                System.out.println("✓ " + res.get("name") + " | score=" + r.get("score")
                        + " | 命中=" + r.get("matched"));
            }
        }
        System.out.println("共 " + n + " 份合格简历，已写入 demo/resume_parsed.csv 与 demo/scout_score.csv");
    }
}

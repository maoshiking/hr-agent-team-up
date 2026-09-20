package com.hragent.agent.scout;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.common.NoticeHtml;
import com.hragent.common.Texts;
import com.hragent.tool.AgentTools;
import com.hragent.tool.ResumeParserTool;
import com.hragent.tool.ToolRegistry;
import static com.hragent.common.Jsons.esc;
import static com.hragent.common.Jsons.asMap;

import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            "可用的工具：resume_parser（把简历原始文本解析成结构化字段）。\n" +
            "职责：根据 JD，先把候选人简历结构化为 resume，再按 JD 做匹配打分。\n" +
            "输入字段：jd（结构化岗位，由第一步「招聘分析师」产出）、resume（简历原始文本）、" +
            "逐条核对清单（分析师提出的每一条硬性要求与加分项，已编号，必须逐条给回执）。\n" +
            "流程：当 resume 是原始文本时，先调用 resume_parser 工具（参数 text=简历原文），再用解析结果生成 resume 并打分。\n" +
            "最终只输出一个纯 JSON 对象（不要任何解释文字、不要 markdown 代码块），字段：\n" +
            "  - resume: { name, years, skills[], experiences[], education, expected_salary }\n" +
            "      education 必须是**对象数组**，每条只含这四个字段（不要加别的键）：\n" +
            "        { school 学校, major 专业, degree 学历, year 学年 }\n" +
            "        year 写成「2018-2022」这种区间；材料里没有的字段给空字符串，不要编造\n" +
            "      expected_salary：简历里**写明的**期望薪资原文（如「期望 18-20K」）；没写就给空字符串，绝不猜\n" +
            "  - dimensions: 评分维度，**只给档位，不要给总分**，四个维度各一项 { key, grade, evidence }：\n" +
            "      key=hard_requirements 硬性要求（权重 35%）\n" +
            "      key=core_skills       核心技能（权重 30%）\n" +
            "      key=experience_fit    经历相关性（权重 20%）\n" +
            "      key=nice_to_have      加分项（权重 15%）\n" +
            "      grade 只能取 must / mostly / partly / no，含义：must=完全符合、mostly=大部分符合、partly=部分符合、no=不符合\n" +
            "      evidence 必须**引用简历原文片段**作为依据；找不到原文依据就给 partly 并说明，禁止编造\n" +
            "  - checks: 逐条核对回执，{ no, verdict, evidence }。\n" +
            "      no 就是「逐条核对清单」里的编号，**清单里有几条就必须回几条，一条都不许漏、不许合并、不许自己加**；\n" +
            "      requirement 原文不用你抄（系统按编号回填），你只给判定与依据；\n" +
            "      verdict 只能取 met / partial / unmet：met=简历有明确依据满足该条；" +
            "partial=只满足一部分或依据不充分；unmet=明确不满足，或简历里完全找不到该条依据；\n" +
            "      evidence 必须引用简历原文片段；简历完全未提该条时写「简历未提及」。\n" +
            "      注意：硬性要求里只要有一条被判 unmet，系统就会**一票否决**（不论总分多高），请如实判定。\n" +
            "  - matched: [{skill, evidence}] 命中的技能与证据\n" +
            "  - gaps: [] 缺口\n" +
            "  - reason: 完整思考过程，**逐维说明档位判定依据**（为什么不是更高或更低一档），再**逐条说明核对清单里每一条的判定理由**，最后说明 matched/gaps 的判定理由\n" +
            "注意：总分与 shortlist/hold/reject 结论由系统按权重自动计算，你不需要输出 score 和 verdict。\n" +
            "reason 要详尽展开思考过程（不要只写四段式简句）；禁止编造证据。";

    private final DeepSeekClient client = new DeepSeekClient();
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** 本 agent 的 function-calling 工具集：只暴露 resume_parser，让模型自主决定"解析简历"。 */
    private static final ToolRegistry SCREEN_TOOLS = new ToolRegistry();
    static {
        SCREEN_TOOLS.register(new ResumeParserTool());
    }

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            Map<String, Object> result = screenOne(payload);
            List<Map<String, Object>> results = List.of(result);
            writeReports(asMap(payload.get("jd")), results, select(results, 1), 1);
            return result;
        } catch (Exception e) {
            throw new RuntimeException("ScoutAgent 执行失败", e);
        }
    }

    /** 核心：单份简历 解析→打分→规范化→校验，返回结果 Map（不写文件）。 */
    private Map<String, Object> screenOne(Map<String, Object> payload) throws Exception {
        Map<String, Object> jd = asMap(payload.get("jd"));
        String resume = String.valueOf(payload.getOrDefault("resume", ""));

        // 逐条核对清单由**代码**从「招聘分析师」的 JD 生成：要求原文只来自 A，模型只回判定与依据。
        // 这样 A→B 的交接不会在模型嘴里被改写或漏项，条数也能逐条校验。
        List<Map<String, Object>> checklist = buildChecklist(jd);
        String userInput = buildUserInput(jd, resume, checklist);

        Map<String, Object> result = client.runWithToolsJson(ROLE, userInput, SCREEN_TOOLS);

        // 兜底：把 resume 规范成契约字段 {name,years,skills[],experiences[],education}
        result.put("resume", normalizeResume(result.get("resume"), new LinkedHashMap<>()));

        // 逐条核对回执：**硬性要求**漏判会直接影响录不录，必须补问一次；
        // 加分项漏判只标「未判定」，不为它多花一次往返（时间也是成本）。
        List<Map<String, Object>> checks = mergeChecks(checklist, result.get("checks"));
        List<Integer> missingGate = missingGateNos(checks);
        if (!missingGate.isEmpty()) {
            Map<String, Object> retry = client.runWithToolsJson(ROLE,
                    userInput + "\n注意：你上一次的回复漏了编号 " + missingGate + " 的核对回执。"
                            + "请只输出这些编号的 checks（{ no, verdict, evidence }），其余字段留空即可。",
                    SCREEN_TOOLS);
            checks = mergeChecks(checklist, result.get("checks"), retry.get("checks"));
        }

        // 2) 分数与结论由代码算（模型只给档位与判定），模型就算塞了 score/verdict 也一律覆盖
        result.remove("score");
        result.remove("verdict");
        // checks 是给模型的**对内协议字段**（每条只有 no/verdict/evidence），
        // 补全后的成品是 requirementChecks（带类别、A 的要求原文、判定文案）。
        // 协议字段必须删掉：留着它前端会同时渲染「残缺的一份 + 完整的一份」。
        result.remove("checks");
        applyScoring(result, checks);
        return result;
    }

    /** 逐条核对清单：把 A 的 hard_requirements 与 nice_to_have 编号列出，要求原文原样保留 */
    private static List<Map<String, Object>> buildChecklist(Map<String, Object> jd) {
        List<Map<String, Object>> list = new ArrayList<>();
        addChecklist(list, jd.get("hard_requirements"), "hard");
        addChecklist(list, jd.get("nice_to_have"), "nice");
        return list;
    }

    private static void addChecklist(List<Map<String, Object>> out, Object arr, String kind) {
        if (!(arr instanceof List<?> items)) return;
        for (Object o : items) {
            String text = String.valueOf(o == null ? "" : o).trim();
            if (text.isEmpty()) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", out.size() + 1);
            row.put("kind", kind);
            row.put("kindLabel", "hard".equals(kind) ? "硬性要求" : "加分项");
            row.put("requirement", text);
            out.add(row);
        }
    }

    /** 澄清后的判定取值：满足 / 部分满足 / 不满足 / 未判定（模型没给回执） */
    private static final Map<String, String> VERDICT_CN =
            Map.of("met", "满足", "partial", "部分满足", "unmet", "不满足", "unjudged", "未判定");

    /** 判定值归一：模型可能写 must/yes/不满足 等各种变体，统一到四档 */
    private static String normalizeVerdict(Object raw) {
        String v = String.valueOf(raw == null ? "" : raw).trim().toLowerCase();
        if (v.isEmpty()) return "unjudged";
        if (v.contains("unmet") || v.contains("不满足") || v.contains("未满足") || v.contains("不符合")
                || v.equals("no") || v.contains("fail")) {
            return "unmet";
        }
        if (v.contains("partial") || v.contains("partly") || v.contains("部分")) return "partial";
        if (v.contains("met") || v.contains("满足") || v.contains("符合") || v.equals("yes")
                || v.contains("must") || v.contains("mostly")) {
            return "met";
        }
        return "unjudged";
    }

    /**
     * 用模型回执填充清单：要求原文一律取自清单（即 A 的原文），只有判定与依据来自模型。
     * 多个回执按顺序合并，先到的优先（补问不覆盖已给的判定）。
     */
    @SafeVarargs
    private static List<Map<String, Object>> mergeChecks(List<Map<String, Object>> checklist,
                                                         Object... modelReplies) {
        Map<Integer, Map<?, ?>> given = new LinkedHashMap<>();
        for (Object reply : modelReplies) {
            if (!(reply instanceof List<?> list)) continue;
            for (Object o : list) {
                if (!(o instanceof Map<?, ?> m)) continue;
                Integer no = intOrNull(m.get("no"));
                if (no != null && !given.containsKey(no)) given.put(no, m);
            }
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> item : checklist) {
            int no = (Integer) item.get("no");
            Map<?, ?> m = given.get(no);
            String verdict = normalizeVerdict(m == null ? null : m.get("verdict"));
            Map<String, Object> row = new LinkedHashMap<>(item);
            row.put("verdict", verdict);
            row.put("verdictLabel", VERDICT_CN.get(verdict));
            row.put("evidence", m == null || m.get("evidence") == null ? "" : String.valueOf(m.get("evidence")));
            // 完全没回这一条（判定非法值仍算 unjudged，但不等于「没回」）
            row.put("noReply", m == null);
            rows.add(row);
        }
        return rows;
    }

    /** 模型没给回执的编号（清单里有、回执里没有） */
    private static List<Integer> missingNos(List<Map<String, Object>> checks) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> c : checks) {
            if (!"unjudged".equals(c.get("verdict"))) continue;
            Object no = c.get("no");
            if (no instanceof Integer i) out.add(i);
        }
        return out;
    }

    /** 漏判的**硬性要求**编号：只有这些会影响录用结论，值得再问一次 */
    private static List<Integer> missingGateNos(List<Map<String, Object>> checks) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> c : checks) {
            if (!"hard".equals(c.get("kind")) || !"unjudged".equals(c.get("verdict"))) continue;
            Object no = c.get("no");
            if (no instanceof Integer i) out.add(i);
        }
        return out;
    }

    /** 拼给模型的输入：jd + resume + 编号化的逐条核对清单 */
    private static String buildUserInput(Map<String, Object> jd, String resume,
                                         List<Map<String, Object>> checklist) {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("jd", jd);
        in.put("resume", resume);
        StringBuilder sb = new StringBuilder("请根据以下输入完成任务。\n输入 JSON：\n").append(in);
        if (!checklist.isEmpty()) {
            sb.append("\n\n逐条核对清单（编号 → 要求，checks 必须逐条回执，一条都不许漏）：\n");
            for (Map<String, Object> c : checklist) {
                sb.append(c.get("no")).append(". [").append(c.get("kindLabel")).append("] ")
                        .append(c.get("requirement")).append("\n");
            }
        }
        return sb.toString();
    }

    /** 评分维度定义：{key, 中文名, 权重%} */
    private static final List<Object[]> DIMENSIONS = List.of(
            new Object[]{"hard_requirements", "硬性要求", 35},
            new Object[]{"core_skills", "核心技能", 30},
            new Object[]{"experience_fit", "经历相关性", 20},
            new Object[]{"nice_to_have", "加分项", 15});

    /** 档位 → 分值（模型做「分类」，代码做「换算」，职责分开） */
    private static final Map<String, Integer> GRADE_VALUE =
            Map.of("must", 100, "mostly", 80, "partly", 55, "no", 25);

    /** 硬性要求逐条判定 → 分值：满足 100 / 部分满足 40（重扣）/ 不满足 0 */
    private static final int HARD_MET_VALUE = 100;
    private static final int HARD_PARTIAL_VALUE = 40;
    private static final int HARD_UNMET_VALUE = 0;
    /** 「部分满足」的容忍条数：不超过这个数不否决（只重扣分），超过即整体视为硬性要求不达标 */
    private static final int HARD_PARTIAL_TOLERANCE = 2;
    /** 总分上限：**任何情况都不出现满分 100**（95 及以上都可以，满分太绝对） */
    private static final int SCORE_CEIL = 95;

    /** 档位 → 中文简写（四个维度都读得通：硬性要求「完全」/ 加分项「无」） */
    private static final Map<String, String> GRADE_CN =
            Map.of("must", "完全", "mostly", "大部分", "partly", "部分", "no", "无");

    /**
     * 把模型给的档位算成总分与结论。
     * 模型只负责「分类」（每维给档位 + 原文依据），加权求和与结论判定全是确定性的：
     * 同一组档位必然得到同一个分数，不会再出现同一份简历两次跑出 82 / 91 的情况。
     *
     * <p>硬门槛（本轮收紧）：招聘分析师提出的硬性要求是**逐条清单**，
     * 任一条被判定「不满足」即一票否决，不管总分多高——这才对得上真实 ATS 的硬性门槛口径。
     */
    private static void applyScoring(Map<String, Object> result, List<Map<String, Object>> checks) {
        Map<String, Map<?, ?>> byKey = new LinkedHashMap<>();
        if (result.get("dimensions") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("key") != null) {
                    byKey.put(String.valueOf(m.get("key")).trim().toLowerCase(), m);
                }
            }
        }

        List<Map<String, Object>> dims = new ArrayList<>();
        double total = 0;
        boolean hardFailed = false;
        for (Object[] def : DIMENSIONS) {
            String key = (String) def[0];
            String label = (String) def[1];
            int weight = (Integer) def[2];

            Map<?, ?> m = byKey.get(key);
            String grade = m == null ? "" : String.valueOf(m.get("grade")).trim().toLowerCase();
            boolean fallback = !GRADE_VALUE.containsKey(grade);
            if (fallback) {
                grade = "partly"; // 缺项或非法档位：按「部分符合」兜底，并在明细里标出来
            }
            int value = GRADE_VALUE.get(grade);
            total += value * weight / 100.0;
            if ("hard_requirements".equals(key) && "no".equals(grade)) {
                hardFailed = true; // 兜底：整块硬性要求都不满足（清单缺失时的最后一道闸）
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("key", key);
            row.put("label", label);
            row.put("weight", weight);
            row.put("grade", grade);
            row.put("gradeLabel", GRADE_CN.get(grade));
            row.put("value", value);
            row.put("fallback", fallback);
            row.put("evidence", m == null || m.get("evidence") == null ? "" : String.valueOf(m.get("evidence")));
            dims.add(row);
        }

        // 逐条硬性要求：扣分与保留都按**条**算，不再由「整块档位」说了算。
        // 口径（按产品要求收紧/放宽过）：
        //   · 满足 100 分、部分满足 40 分（扣大分）、不满足 0 分；
        //   · 只要不是「满足」就扣更多分；
        //   · 不满足 → 一票否决；
        //   · **部分满足不超过 2 条不否决**（只重扣分），超过 2 条整体视为硬性要求不达标。
        List<String> vetoedBy = new ArrayList<>();
        List<String> hardPartial = new ArrayList<>();
        int hardCount = 0;
        int hardMet = 0;
        double hardSum = 0;
        for (Map<String, Object> c : checks) {
            if (!"hard".equals(c.get("kind"))) continue;
            hardCount++;
            String verdict = String.valueOf(c.get("verdict"));
            String text = String.valueOf(c.get("requirement"));
            if ("met".equals(verdict)) {
                hardMet++;
                hardSum += HARD_MET_VALUE;
            } else if ("unmet".equals(verdict)) {
                vetoedBy.add(text);
                hardSum += HARD_UNMET_VALUE;
            } else {
                // partial 与未判定（unjudged）都按「部分满足」算：没拿到证据也不该当成满足
                hardPartial.add(text);
                hardSum += HARD_PARTIAL_VALUE;
            }
        }
        if (hardCount > 0) {
            // 用逐条判定的平均分覆盖「硬性要求」这一维，再按权重重算总分
            int hardValue = (int) Math.round(hardSum / hardCount);
            total = 0;
            for (Map<String, Object> d : dims) {
                if ("hard_requirements".equals(String.valueOf(d.get("key")))) {
                    d.put("value", hardValue);
                    d.put("gradeLabel", hardGradeLabel(hardMet, hardPartial.size(), vetoedBy.size()));
                    d.put("byItem", true);
                }
                total += ((Number) d.get("value")).doubleValue() * ((Number) d.get("weight")).doubleValue() / 100.0;
            }
        }

        if (!vetoedBy.isEmpty()) hardFailed = true;
        boolean tooManyPartial = hardPartial.size() > HARD_PARTIAL_TOLERANCE;
        if (tooManyPartial) hardFailed = true;

        int scoreRaw = (int) Math.round(total);
        int score = Math.max(0, Math.min(SCORE_CEIL, scoreRaw));
        boolean capped = scoreRaw > SCORE_CEIL;
        String verdict;
        if (hardFailed) {
            verdict = "reject";
        } else if (score >= 72) {
            verdict = "shortlist";
        } else if (score >= 55) {
            verdict = "hold";
        } else {
            verdict = "reject";
        }

        StringBuilder note = new StringBuilder();
        for (int i = 0; i < dims.size(); i++) {
            Map<String, Object> d = dims.get(i);
            if (i > 0) note.append(" + ");
            note.append(d.get("label")).append(" ").append(d.get("value")).append("×").append(d.get("weight")).append("%");
        }
        if (hardCount > 0) {
            note.append("；硬性要求逐条判定：满足 ").append(hardMet).append(" 条");
            if (!hardPartial.isEmpty()) note.append("、部分满足 ").append(hardPartial.size()).append(" 条（每条按 40 分重扣）");
            if (!vetoedBy.isEmpty()) note.append("、不满足 ").append(vetoedBy.size()).append(" 条");
        }
        if (!vetoedBy.isEmpty()) {
            note.append("。硬性要求未满足（").append(joinClauses(vetoedBy)).append("），一票否决");
        } else if (tooManyPartial) {
            note.append("。部分满足 ").append(hardPartial.size()).append(" 条，超过 ")
                    .append(HARD_PARTIAL_TOLERANCE).append(" 条，整体视为硬性要求不达标，一票否决");
        } else if (!hardPartial.isEmpty()) {
            note.append("。部分满足未超过 ").append(HARD_PARTIAL_TOLERANCE).append(" 条，不否决，但已在总分里重扣");
        } else if (hardFailed) {
            note.append("。硬性要求整体不满足，一票否决");
        }
        if (capped) {
            note.append("。原始分 ").append(scoreRaw).append("，按上限 ").append(SCORE_CEIL).append(" 记（不出现满分）");
        }
        List<Integer> missing = missingNos(checks);
        if (!missing.isEmpty()) {
            note.append("；注：编号 ").append(missing).append(" 未拿到核对回执");
        }

        result.put("dimensions", dims);
        result.put("requirementChecks", checks);
        result.put("vetoedBy", vetoedBy);
        result.put("hardPartial", hardPartial);
        result.put("hardMet", hardMet);
        result.put("scoreRaw", scoreRaw);
        result.put("scoreCapped", capped);
        result.put("score", score);
        result.put("verdict", verdict);
        result.put("scoreNote", "= " + note);
    }

    /** 硬性要求这一维的档位标签：直接给逐条统计，一眼看出满足几条、部分几条 */
    private static String hardGradeLabel(int met, int partial, int unmet) {
        StringBuilder sb = new StringBuilder("满足 ").append(met);
        if (partial > 0) sb.append(" / 部分 ").append(partial);
        if (unmet > 0) sb.append(" / 不满足 ").append(unmet);
        return sb.toString();
    }

    /** 逐条核对的一组（硬性要求 / 加分项）：组头说明后果，左侧竖线用类别色区分 */
    private static void appendCheckGroup(StringBuilder sb, String title, String hint,
                                         List<Map<?, ?>> rows, String color) {
        if (rows.isEmpty()) return;
        sb.append("<div style=\"border-left:3px solid ").append(color).append(";padding-left:10px;margin:0 0 14px\">")
                .append("<p class=\"hint\"><b>").append(esc(title)).append("</b>（").append(rows.size())
                .append(" 条）").append(esc(hint)).append("</p>")
                .append("<div class=\"checks\">");
        for (Map<?, ?> c : rows) {
            String cverdict = String.valueOf(c.get("verdict"));
            String cvcls = "met".equals(cverdict) ? " ok"
                    : ("unmet".equals(cverdict) ? " bad" : ("partial".equals(cverdict) ? " warn" : ""));
            sb.append("<div class=\"chk\">")
                    .append("<span class=\"ckn\">").append(c.get("no")).append("</span>")
                    .append("<span class=\"ckq\">").append(esc(String.valueOf(c.get("requirement"))))
                    .append(Boolean.TRUE.equals(c.get("noReply")) ? "<i class=\"fb\">无回执</i>" : "")
                    .append("</span>")
                    .append("<span class=\"ckv").append(cvcls).append("\">")
                    .append(esc(String.valueOf(c.get("verdictLabel")))).append("</span>")
                    .append("</div>");
            String cev = String.valueOf(c.get("evidence") == null ? "" : c.get("evidence"));
            if (!cev.isBlank()) {
                sb.append("<div class=\"cke\">").append(esc(cev)).append("</div>");
            }
        }
        sb.append("</div></div>");
    }

    /** no 字段可能是 Integer 也可能是字符串，统一取整 */
    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return n.intValue();
        try {
            return Integer.valueOf(String.valueOf(o).trim());
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** 结论值中文化，避免 CSV 里出现 shortlist 这类原始值 */
    private static String verdictCn(Object v) {
        String s = String.valueOf(v == null ? "" : v).toLowerCase();
        if (s.contains("short")) return "进入下一轮";
        if (s.contains("reject") || s.contains("fail")) return "淘汰";
        return "待定";
    }

    /** 字段名 → 中文：CSV 里不再出现 org= / detail= 这种 Java Map 的 toString。 */
    private static final Map<String, String> FIELD_CN = Map.ofEntries(
            Map.entry("org", "单位"), Map.entry("role", "职位"), Map.entry("period", "时间"),
            Map.entry("detail", "详情"), Map.entry("company", "公司"), Map.entry("description", "描述"),
            Map.entry("achievements", "成果"), Map.entry("start", "开始"), Map.entry("end", "结束"),
            Map.entry("major", "专业"), Map.entry("school", "学校"), Map.entry("degree", "学历"),
            Map.entry("skill", "技能"), Map.entry("evidence", "依据"), Map.entry("item", "核查项"),
            Map.entry("risk", "风险"), Map.entry("text", "问题"), Map.entry("intent", "考察意图"),
            Map.entry("name", "姓名"), Map.entry("years", "工作年限"), Map.entry("skills", "技能"),
            Map.entry("experiences", "工作经历"), Map.entry("education", "教育背景"),
            Map.entry("projects", "项目经历"), Map.entry("certificates", "证书"), Map.entry("courses", "课程"),
            Map.entry("awards", "荣誉奖项"), Map.entry("languages", "语言能力"), Map.entry("highlights", "亮点"));

    /** 把任意嵌套结构渲染成中文可读文本：字段之间用「；」，条目之间换行。 */
    private static String humanize(Object o) {
        if (o == null) return "";
        if (o instanceof List<?> list) {
            StringBuilder sb = new StringBuilder();
            for (Object it : list) {
                String s = humanize(it);
                if (s.isBlank()) continue;
                if (sb.length() > 0) sb.append("\n");
                sb.append(s);
            }
            return sb.toString();
        }
        if (o instanceof Map<?, ?> map) {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                String v = humanize(e.getValue());
                if (v.isBlank()) continue;
                if (sb.length() > 0) sb.append("；");
                String k = String.valueOf(e.getKey());
                sb.append(FIELD_CN.getOrDefault(k, k)).append("：").append(v);
            }
            return sb.toString();
        }
        return String.valueOf(o);
    }

    /** 🔧 真调用 doc_writer：初筛报告（含录用决策）+ 每位落选者的未通过通知。 */
    public void writeReports(Map<String, Object> jd, List<Map<String, Object>> results,
                             List<Map<String, Object>> decisions, int hireCount) {
        try {
            AgentTools.writeDoc("html", "简历初筛报告.html", buildReportHtml(results, jd, decisions, hireCount));
            // 未通过通知：人手一份，文件名带姓名（重名时补候选人编号）
            // 版式与措辞由 NoticeHtml 统一渲染（面试阶段的落选通知走同一个类）
            Set<String> used = new HashSet<>();
            for (Map<String, Object> d : decisions) {
                if (Boolean.TRUE.equals(d.get("selected"))) continue;
                String name = String.valueOf(d.get("name"));
                String file = "未通过通知-" + NoticeHtml.safeName(name);
                if (!used.add(file)) file = file + "-" + d.get("id");
                Map<String, Object> r = results.get((Integer) d.get("index"));
                AgentTools.writeDoc("html", file + ".html",
                        NoticeHtml.render(NoticeHtml.Stage.SCREENING, jdTitle(jd), name,
                                gapNames(r.get("gaps"), 3)));
            }
        } catch (Exception e) {
            throw new RuntimeException("ScoutAgent 落盘失败", e);
        }
    }

    /**
     * 初筛多份简历：全部打分（不再命中一个就提前结束），只返回结果不落盘。
     * 选拔交给 {@link #select}，落盘交给 {@link #writeReports}——拆开是为了让
     * 「先打分 → 再按名额选拔 → 再出报告」这条链路可测。
     */
    public List<Map<String, Object>> screenAll(Map<String, Object> jd, List<String> resumes) {
        List<Map<String, Object>> results = new ArrayList<>();
        for (String resume : resumes) {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("jd", jd);
            payload.put("resume", resume);
            try {
                results.add(screenOne(payload));
            } catch (Exception e) {
                throw new RuntimeException("简历初筛失败：" + e.getMessage(), e);
            }
        }
        return results;
    }

    /**
     * 筛查单份简历——供前端「逐人并发」调用：每人一次请求，返回一份结果。
     * 一次请求只装一个人，所以既不会被单次超时卡住，也能让前端每批返回就展示思考。
     */
    public Map<String, Object> screenSingle(Map<String, Object> jd, String resume) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("jd", jd);
        payload.put("resume", resume);
        try {
            return screenOne(payload);
        } catch (Exception e) {
            throw new RuntimeException("简历初筛失败：" + e.getMessage(), e);
        }
    }

    /**
     * 按「计划录用人数 K」选拔：得分降序，前 K 名入选（被判定淘汰的不占名额）。
     * 规则：K 是**上限**不是硬名额——不足就不凑数；第 K 名与后面同分时一并入选并标「并列」。
     *
     * @return 决策行，每行 {index, rank, id, name, score, verdict, selected, tie, rejectReason}
     */
    public static List<Map<String, Object>> select(List<Map<String, Object>> results, int hireCount) {
        int k = Math.max(1, hireCount);
        final List<Map<String, Object>> src = results == null ? List.of() : results;

        // 得分降序；同分保持提交顺序，保证结果稳定可复现
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < src.size(); i++) idx.add(i);
        idx.sort((a, b) -> {
            int c = Integer.compare(scoreOf(src.get(b)), scoreOf(src.get(a)));
            return c != 0 ? c : Integer.compare(a, b);
        });

        List<Map<String, Object>> rows = new ArrayList<>();
        int taken = 0;
        Integer cutoff = null;
        for (int rank = 0; rank < idx.size(); rank++) {
            int i = idx.get(rank);
            Map<String, Object> r = src.get(i);
            Map<String, Object> resume = asMap(r.get("resume"));
            int score = scoreOf(r);
            // 只有判定为 shortlist（进入下一轮）的候选人才有资格占名额：
            // hold（待定）和 reject（淘汰）都不占，和原流程「只有 shortlist 才推进」保持一致
            boolean eligible = "shortlist".equals(String.valueOf(r.get("verdict")));

            boolean selected;
            boolean tie = false;
            if (!eligible) {
                selected = false;
            } else if (taken < k) {
                selected = true;
                taken++;
                cutoff = score;
            } else {
                // 第 K 名之后，同分者一并入选
                selected = cutoff != null && score == cutoff;
                tie = selected;
            }

            Map<String, Object> row = new LinkedHashMap<>();
            row.put("index", i);
            row.put("id", String.format("C%02d", i + 1));
            row.put("rank", rank + 1);
            row.put("name", resume.get("name") == null ? ("第 " + (i + 1) + " 位候选人") : String.valueOf(resume.get("name")));
            row.put("score", score);
            row.put("verdict", verdictCn(r.get("verdict")));
            row.put("selected", selected);
            row.put("tie", tie);
            row.put("rejectReason", selected ? "" : rejectReason(r, rank + 1, k));
            rows.add(row);
        }
        return rows;
    }

    /**
     * 把若干条要求/差距拼成一句话：**去掉每条自己的句末标点**，再用顿号连接。
     *
     * <p>踩过的坑：A 提的硬性要求本身就带句号（「…等相关专业优先。」），
     * 直接 `String.join("、", ...)` 会拼出「…优先。、持有…」这种叠标点，
     * 落选原因里一眼就能看出来。差距项默认不带标点，这里顺手清理也不亏。
     */
    private static String joinClauses(List<String> items) {
        List<String> clean = new ArrayList<>();
        for (String raw : items) {
            String t = String.valueOf(raw == null ? "" : raw).trim();
            t = t.replaceAll("[。．.；;、,，：:\\s]+$", "").trim();   // 去句末
            t = t.replaceAll("^[、,，；;：:\\s]+", "").trim();        // 去句首多余的停顿
            if (!t.isEmpty()) clean.add(t);
        }
        return String.join("、", clean);
    }

    /** 落选原因：区分「硬性要求未满足」「能力不达标」「待定」「名额已满」四种，通知函和报告都要用 */
    private static String rejectReason(Map<String, Object> r, int rank, int k) {
        String verdict = String.valueOf(r.get("verdict") == null ? "" : r.get("verdict")).toLowerCase();
        List<String> gaps = gapNames(r.get("gaps"), 3);
        String gapText = gaps.isEmpty() ? "" : "；主要差距：" + joinClauses(gaps);
        // 逐条硬门槛：把「分析师提的哪一条没过」直接写进理由，交接链路一目了然
        List<String> vetoed = stringList(r.get("vetoedBy"));
        if (!vetoed.isEmpty()) {
            return "未满足该岗位的硬性要求：" + joinClauses(vetoed) + "。";
        }
        if (verdict.contains("reject") || verdict.contains("fail")) {
            return "综合评估未达到该岗位的基本要求" + gapText + "。";
        }
        if (!verdict.contains("short")) {
            return "评估结论为待定：现有材料还不足以确认匹配度" + gapText + "。";
        }
        return "本次计划录用 " + k + " 人，评估排名第 " + rank + " 位，暂未进入录用名单" + gapText + "。";
    }

    /** 取差距项的名字（最多 n 个） */
    private static List<String> gapNames(Object gaps, int n) {
        List<String> out = new ArrayList<>();
        if (gaps instanceof List<?> list) {
            for (Object it : list) {
                if (out.size() >= n) break;
                if (it instanceof Map<?, ?> m) {
                    Object s = m.get("skill");
                    if (s == null) s = m.get("item");
                    if (s != null && !String.valueOf(s).isBlank()) out.add(String.valueOf(s));
                } else if (it != null && !String.valueOf(it).isBlank()) {
                    out.add(String.valueOf(it));
                }
            }
        }
        return out;
    }

    private static final String STYLE = """
            :root{--g:#3f9474;--b:#3f8dc2;--gs:#e9f6f0;--bs:#e9f3fb;--ink:#2f4048;--mut:#74878f;--line:#e7efed}
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;color:var(--ink);line-height:1.65;background:linear-gradient(165deg,#e5f6ee,#e8f2fb 46%,#f0f7fc);background-attachment:fixed;padding:24px 14px;-webkit-font-smoothing:antialiased}
            .page{position:relative;max-width:860px;margin:0 auto;background:#fff;border-radius:18px;box-shadow:0 12px 34px rgba(60,120,120,.14);overflow:hidden}
            .hero{background:linear-gradient(135deg,#3f9474,#63b896 30%,#4f9fd0 72%,#3f8dc2);color:#fff;padding:30px 42px 24px}
            .hero .crumb{font-size:11.5px;letter-spacing:2px;opacity:.9;margin-bottom:8px}
            .hero h1{font-size:26px;font-weight:700;line-height:1.3}
            .hero .sub{font-size:13.5px;opacity:.95;margin-top:8px}
            .body{padding:22px 42px 10px}
            .eyebrow{font-size:10.5px;letter-spacing:2px;color:var(--mut);font-weight:600;margin:22px 0 2px}
            h2{display:flex;align-items:center;gap:9px;font-size:17px;font-weight:700}
            h2::before{content:"";width:4px;height:16px;border-radius:2px;flex:0 0 auto;background:linear-gradient(180deg,var(--g),var(--b))}
            .card{border:1px solid var(--line);border-radius:12px;background:#fff;padding:14px 18px;margin-top:8px}
            .grid4{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:8px}
            .kpi{background:var(--gs);border:1px solid #d5eadf;border-radius:12px;padding:12px;text-align:center}
            .kpi.blue{background:var(--bs);border-color:#d2e5f3}
            .kpi.warn{background:#fdf3e3;border-color:#f0dcb6}
            .kpi .k{font-size:11.5px;color:var(--mut)}
            .kpi .v{font-size:17px;font-weight:700;color:#2c7359;margin-top:3px}
            .kpi.blue .v{color:#2c6392}
            .kpi.warn .v{color:#a8722c}
            .cand{border:1px solid var(--line);border-radius:14px;background:#fbfdfc;padding:16px 18px 18px;margin-top:10px}
            .cand-head{display:flex;align-items:center;gap:10px;flex-wrap:wrap}
            .cand-head .idx{display:inline-flex;align-items:center;justify-content:center;width:22px;height:22px;border-radius:50%;background:var(--g);color:#fff;font-size:12px;font-weight:700;flex:0 0 auto}
            .cand-head .cname{font-size:16px;font-weight:700;color:#26454f}
            .cand-head .cscore{margin-left:auto;font-size:20px;font-weight:700;color:#2c7359}
            .cand-head .cscore em{font-size:12px;font-style:normal;font-weight:600;color:var(--mut);margin-left:1px}
            .cverd{font-size:12px;font-weight:600;padding:3px 11px;border-radius:999px;border:1px solid #cfe9dc;background:var(--gs);color:#2c7359}
            .cverd.warn{background:#fdf3e3;border-color:#f0dcb6;color:#a8722c}
            .cverd.bad{background:#fbeaea;border-color:#f0cccc;color:#b04a4a}
            .chire{font-size:12px;font-weight:600;padding:3px 11px;border-radius:999px;background:#fdf3e3;border:1px solid #f0dcb6;color:#a8722c}
            .chire.ok{background:var(--gs);border-color:#b6ddc7;color:#22664a}
            .pick{margin-top:8px;border:1px solid #b6ddc7;border-radius:12px;background:var(--gs);padding:14px 18px}
            .pick h3,.drop h3{font-size:13px;font-weight:700;margin-bottom:8px}
            .pick h3{color:#22664a}
            .drop h3{color:#a8722c}
            .pick ul,.drop ul{margin:0;padding-left:18px;list-style:disc}
            .pick li{font-size:14px;color:#2f4f45;margin:4px 0}
            .pick li b{font-weight:700}
            .pick li em{font-style:normal;font-weight:700;color:#2c7359;margin-left:8px}
            .pick li i{font-style:normal;font-size:11.5px;color:#7d8f6e;margin-left:8px;padding:1px 7px;border-radius:999px;background:rgba(255,255,255,.7)}
            .drop{margin-top:10px;border:1px solid #f0dcb6;border-radius:12px;background:#fdf8ee;padding:14px 18px}
            .drop li{font-size:13.5px;color:#5f5443;margin:5px 0}
            .drop li em{font-style:normal;font-weight:700;color:#a8722c;margin-left:8px}
            .drop li span{display:block;font-size:12.5px;color:#8d8272;margin-top:2px}
            .none{font-size:12.5px;color:#74878f;margin-top:6px}
            .cbasic{font-size:12.5px;color:var(--mut);margin-top:6px}
            /* 教育背景：固定四列 学校 / 专业 / 学历 / 学年，风格与前端一致（玻璃表头 + 斑马纹）。
               报告底色是白卡片，所以斑马纹用淡绿而不是半透明白，否则看不出交替 */
            .edu{width:100%;table-layout:fixed;border-collapse:separate;border-spacing:0;margin-top:4px;
                  font-size:12.5px;border:1px solid rgba(96,154,118,.22);border-radius:12px;overflow:hidden;
                  box-shadow:0 8px 22px rgba(60,100,78,.07)}
            .edu th{text-align:left;font-weight:600;font-size:11.5px;letter-spacing:.08em;color:#3f7a5e;
                  padding:9px 12px;background:rgba(240,248,243,.92);
                  border-bottom:1px solid rgba(96,154,118,.22);white-space:normal;word-break:break-word}
            .edu td{padding:10px 12px;color:#38624c;word-break:break-word;line-height:1.7}
            .edu tbody tr:nth-child(odd){background:#f9fcfa}
            .edu tbody tr:nth-child(even){background:#fff}
            .edu tbody tr:hover{background:#f3faf6}
            .edu th:nth-child(1),.edu td:nth-child(1){width:30%}
            .edu th:nth-child(2),.edu td:nth-child(2){width:22%}
            .edu th:nth-child(3),.edu td:nth-child(3){width:16%}
            .edu th:nth-child(4),.edu td:nth-child(4){width:32%}
            .dims{margin-top:6px;border:1px solid var(--line);border-radius:12px;background:#fbfdfc;padding:12px 16px}
            .dim{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:6px 0}
            .dim .dn{flex:0 0 84px;font-size:13px;font-weight:600;color:#33525c}
            .dim .dn .fb{font-style:normal;font-size:10.5px;font-weight:600;color:#a8722c;background:#fdf3e3;
                  border:1px solid #f0dcb6;border-radius:999px;padding:0 6px;margin-left:6px}
            .dim .dt{flex:1 1 160px;min-width:120px;height:8px;border-radius:999px;background:rgba(63,148,116,.14);overflow:hidden}
            .dim .dt i{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#3f9474,#5eb37e)}
            .dim .dv{flex:0 0 34px;text-align:right;font-size:13px;font-weight:700;color:#2c7359}
            .dim .dg{flex:0 0 auto;font-size:11.5px;color:#5f7d6c}
            .dim .dw{flex:0 0 auto;font-size:11px;color:#9aa8ad}
            .dev{font-size:12px;color:#7c8f96;line-height:1.7;padding:0 0 6px 94px}
            .dnote{margin-top:8px;font-size:11.5px;color:#9aa8ad;line-height:1.7}
            .checks{margin-top:6px;border:1px solid var(--line);border-radius:12px;background:#fbfdfc;padding:12px 16px}
            .chk{display:flex;align-items:baseline;gap:9px;flex-wrap:wrap;padding:6px 0}
            .chk .ckn{flex:0 0 auto;display:inline-flex;align-items:center;justify-content:center;width:19px;height:19px;
                  border-radius:50%;background:var(--gs);color:#2c7359;font-size:11px;font-weight:700}
            .chk .ckt{flex:0 0 auto;font-size:11px;font-weight:600;color:#5f7d6c;background:#f0f6f2;
                  border:1px solid #dbeae1;border-radius:999px;padding:1px 8px}
            .chk .ckq{flex:1 1 180px;font-size:13px;color:#33525c}
            .chk .ckq .fb{font-style:normal;font-size:10.5px;font-weight:600;color:#a8722c;background:#fdf3e3;
                  border:1px solid #f0dcb6;border-radius:999px;padding:0 6px;margin-left:6px}
            .chk .ckv{flex:0 0 auto;font-size:11.5px;font-weight:600;color:#5f7d6c}
            .chk .ckv.ok{color:#2c7359}
            .chk .ckv.warn{color:#a8722c}
            .chk .ckv.bad{color:#b04a4a}
            .cke{font-size:12px;color:#7c8f96;line-height:1.7;padding:0 0 6px 116px}
            .hint{font-size:11.5px;font-weight:600;color:#4a7a5f;letter-spacing:.5px;margin-top:12px}
            .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}
            .chip{display:inline-flex;padding:3px 11px;border-radius:999px;font-size:12.5px;background:var(--gs);color:#2c7359;border:1px solid #cfe9dc}
            .chip.blue{background:var(--bs);color:#2c6392;border-color:#d2e5f3}
            .exp{padding:8px 0;border-bottom:1px dashed #e3eeea}
            .exp:last-child{border-bottom:none;padding-bottom:0}
            .exp-head{font-size:13px;font-weight:600;color:#33525c}
            .exp-detail{font-size:12.5px;color:#65787f;margin-top:3px;line-height:1.75}
            .m{display:flex;gap:9px;align-items:flex-start;padding:7px 0;border-bottom:1px dashed #e3eeea}
            .m:last-child{border-bottom:none;padding-bottom:0}
            .m .mk{flex:0 0 auto;font-size:11px;font-weight:600;padding:2px 8px;border-radius:999px;margin-top:1px}
            .m.hit .mk{background:var(--gs);color:#2c7359;border:1px solid #cfe9dc}
            .m.gap .mk{background:#fdf3e3;color:#a8722c;border:1px solid #f0dcb6}
            .m .msk{font-size:13px;font-weight:600;color:#33525c}
            .m .mev{font-size:12.5px;color:#65787f;margin-top:2px;line-height:1.75}
            footer{padding:16px 42px 24px;text-align:center;color:#94a6ad;font-size:11.5px;letter-spacing:.5px}
            @media(max-width:640px){.grid4{grid-template-columns:1fr 1fr}.hero{padding:22px 20px 18px}.body{padding:16px 18px 8px}footer{padding:14px 18px 20px}}
            """;

    private String buildReportHtml(List<Map<String, Object>> results, Map<String, Object> jd,
                                  List<Map<String, Object>> decisions, int hireCount) {
        String time = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String title = jd == null || jd.get("title") == null ? "待定岗位" : String.valueOf(jd.get("title"));
        String jdId = jd == null || jd.get("jd_id") == null ? "—" : String.valueOf(jd.get("jd_id"));

        int picked = 0;
        for (Map<String, Object> d : decisions) {
            if (Boolean.TRUE.equals(d.get("selected"))) picked++;
        }
        int dropped = decisions.size() - picked;

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>简历初筛报告</title><style>").append(STYLE).append("</style></head><body><div class=\"page\">");
        sb.append("<header class=\"hero\"><div class=\"crumb\">简历初筛 · RESUME SCREENING</div>");
        sb.append("<h1>").append(esc(title)).append(" · 候选人初筛报告</h1>");
        sb.append("<div class=\"sub\">岗位 ID ").append(esc(jdId)).append("　·　计划录用 ").append(hireCount)
                .append(" 人　·　实收 ").append(decisions.size()).append(" 份简历　·　").append(time)
                .append("</div></header><div class=\"body\">");

        sb.append("<div class=\"grid4\">");
        sb.append(kpi("计划录用", String.valueOf(hireCount), "blue"));
        sb.append(kpi("实收简历", String.valueOf(decisions.size()), ""));
        sb.append(kpi("进入录用名单", String.valueOf(picked), ""));
        sb.append(kpi("本次未通过", String.valueOf(dropped), "warn"));
        sb.append("</div>");

        sb.append(sec("决策", "录用决策"));
        sb.append(decisionBlock(decisions, hireCount));

        sb.append(sec("候选人", "逐个候选人评估（按得分排序）"));
        if (decisions.isEmpty()) {
            sb.append("<div class=\"card\">本轮没有收到简历。</div>");
        }
        for (Map<String, Object> d : decisions) {
            Object idx = d.get("index");
            if (!(idx instanceof Integer)) continue;
            sb.append(candidateCard(results.get((Integer) idx), d));
        }

        sb.append("</div><footer>简历猎手 · resume.screen　·　本次共初筛 ").append(decisions.size())
                .append(" 份简历　·　").append(time).append("</footer></div></body></html>");
        return sb.toString();
    }

    /** 录用决策：名额、录用名单、未通过名单（含原因） */
    private static String decisionBlock(List<Map<String, Object>> decisions, int hireCount) {
        StringBuilder pick = new StringBuilder();
        StringBuilder drop = new StringBuilder();
        int picked = 0;
        for (Map<String, Object> d : decisions) {
            if (Boolean.TRUE.equals(d.get("selected"))) {
                picked++;
                pick.append("<li><b>").append(esc(String.valueOf(d.get("name")))).append("</b><em>")
                        .append(d.get("score")).append(" 分</em>")
                        .append(Boolean.TRUE.equals(d.get("tie")) ? "<i>与末位并列</i>" : "")
                        .append("</li>");
            } else {
                drop.append("<li><b>").append(esc(String.valueOf(d.get("name")))).append("</b><em>")
                        .append(d.get("score")).append(" 分</em><span>")
                        .append(esc(String.valueOf(d.get("rejectReason")))).append("</span></li>");
            }
        }
        StringBuilder sb = new StringBuilder();
        sb.append("<div class=\"pick\"><h3>录用名单（计划 ").append(hireCount).append(" 人，实际 ")
                .append(picked).append(" 人）</h3>");
        sb.append(picked > 0 ? "<ul>" + pick + "</ul>"
                : "<p class=\"none\">本轮没有候选人进入录用名单。</p>");
        if (picked < hireCount) {
            sb.append("<p class=\"none\">名额为上限：候选人不达标时不凑数，本次未用满名额。</p>");
        }
        sb.append("</div>");
        if (drop.length() > 0) {
            sb.append("<div class=\"drop\"><h3>本次未通过（").append(decisions.size() - picked)
                    .append(" 人）</h3><ul>").append(drop).append("</ul></div>");
        }
        return sb.toString();
    }

    /** 取岗位名称（通知函标题用） */
    private static String jdTitle(Map<String, Object> jd) {
        return jd == null || jd.get("title") == null ? "该岗位" : String.valueOf(jd.get("title"));
    }

    private static String candidateCard(Map<String, Object> r, Map<String, Object> decision) {
        Map<String, Object> res = asMap(r.get("resume"));
        String name = res.get("name") == null ? "未命名候选人" : String.valueOf(res.get("name"));
        String verdict = verdictCn(r.get("verdict"));
        String vcls = "进入下一轮".equals(verdict) ? "" : ("淘汰".equals(verdict) ? " bad" : " warn");
        boolean hired = decision == null || Boolean.TRUE.equals(decision.get("selected"));

        StringBuilder sb = new StringBuilder("<div class=\"cand\">");
        sb.append("<div class=\"cand-head\"><span class=\"idx\">")
                .append(decision == null ? "1" : decision.get("rank")).append("</span>");
        sb.append("<span class=\"cname\">").append(esc(name)).append("</span>");
        sb.append("<span class=\"cscore\">").append(scoreOf(r)).append("<em>分</em></span>");
        sb.append("<span class=\"cverd").append(vcls).append("\">").append(esc(verdict)).append("</span>");
        sb.append("<span class=\"chire").append(hired ? " ok" : "").append("\">")
                .append(hired ? "进入录用名单" : "本次未通过").append("</span></div>");

        // 简历：年限 + 教育背景（固定四列）+ 技能 + 工作经历，**集中成一块**放在评分之前。
        // 早前是「学历年限在评分明细之前、技能经历在之后」，同一份简历被拆成两半，
        // 看起来就像简历出现了两次；顺序与前端「候选人简历 → 初筛打分」保持一致。
        String years = res.get("years") == null ? "" : String.valueOf(res.get("years")) + " 年经验";
        List<String> skills = stringList(res.get("skills"));
        String exps = experienceHtml(res.get("experiences"));
        String eduTable = educationTable(res.get("education"));
        if (!years.isBlank() || !eduTable.isBlank() || !skills.isEmpty() || !exps.isBlank()) {
            sb.append("<p class=\"hint\">简历</p>");
            if (!years.isBlank()) {
                sb.append("<p class=\"cbasic\">").append(esc(years)).append("</p>");
            }
            if (!eduTable.isBlank()) {
                sb.append("<p class=\"cbasic\">教育背景</p>").append(eduTable);
            }
            if (!skills.isEmpty()) {
                sb.append(chips(skills, false));
            }
            sb.append(exps);
        }

        // 评分明细：分数怎么来的，摆在这里（评委一眼能看到，不用只看到一个孤零零的 88）
        Object dims = r.get("dimensions");
        if (dims instanceof List<?> dimList && !dimList.isEmpty()) {
            sb.append("<p class=\"hint\">评分明细</p><div class=\"dims\">");
            for (Object o : dimList) {
                if (!(o instanceof Map<?, ?> d)) continue;
                int value = d.get("value") instanceof Integer ? (Integer) d.get("value") : 0;
                sb.append("<div class=\"dim\">")
                        .append("<span class=\"dn\">").append(esc(String.valueOf(d.get("label"))))
                        .append(Boolean.TRUE.equals(d.get("fallback")) ? "<i class=\"fb\">缺</i>" : "")
                        .append("</span>")
                        .append("<span class=\"dt\"><i style=\"width:").append(value).append("%\"></i></span>")
                        .append("<span class=\"dv\">").append(value).append("</span>")
                        .append("<span class=\"dg\">").append(esc(String.valueOf(d.get("gradeLabel")))).append("</span>")
                        .append("<span class=\"dw\">权重 ").append(d.get("weight")).append("%</span>")
                        .append("</div>");
                String ev = String.valueOf(d.get("evidence") == null ? "" : d.get("evidence"));
                if (!ev.isBlank()) {
                    sb.append("<div class=\"dev\">").append(esc(ev)).append("</div>");
                }
            }
            sb.append("</div>");
        }

        // 逐条核对：分析师提的每一条要求 → 简历猎手的逐条判定（两个 agent 的交接摆到台面上）。
        // 按「硬性要求 / 加分项」分两组，和页面上保持一致：这两类的后果完全不同。
        Object checksObj = r.get("requirementChecks");
        if (checksObj instanceof List<?> checkList && !checkList.isEmpty()) {
            List<Map<?, ?>> hard = new ArrayList<>();
            List<Map<?, ?>> nice = new ArrayList<>();
            for (Object o : checkList) {
                if (!(o instanceof Map<?, ?> c)) continue;
                if ("hard".equals(String.valueOf(c.get("kind")))) hard.add(c);
                else nice.add(c);
            }
            sb.append("<p class=\"hint\">逐条核对（① 招聘分析师提的要求 → ② 简历猎手逐条判定）</p>");
            appendCheckGroup(sb, "硬性要求", "不满足即一票否决；部分满足不超过 2 条不否决，但按 40 分重扣", hard, "#3f9474");
            appendCheckGroup(sb, "加分项", "只影响得分，不参与一票否决", nice, "#cbbd7c");
        }

        Object note = r.get("scoreNote");
        if (note != null && !String.valueOf(note).isBlank()) {
            sb.append("<p class=\"dnote\">算分说明 ").append(esc(String.valueOf(note))).append("</p>");
        }

        String hit = matchedHtml(r.get("matched"), true);
        if (!hit.isBlank()) {
            sb.append("<p class=\"hint\">匹配项</p>").append(hit);
        }
        String gap = matchedHtml(r.get("gaps"), false);
        if (!gap.isBlank()) {
            sb.append("<p class=\"hint\">差距项</p>").append(gap);
        }

        return sb.append("</div>").toString();
    }

    /** 工作经历：单位 · 职位 · 时间 一行，详情单独一段。 */
    private static String experienceHtml(Object o) {
        if (!(o instanceof List<?> list) || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) {
                sb.append("<div class=\"exp\"><p class=\"exp-detail\">").append(esc(humanize(it))).append("</p></div>");
                continue;
            }
            sb.append("<div class=\"exp\"><p class=\"exp-head\">")
                    .append(esc(join(" · ", m.get("org"), m.get("role"), m.get("period")))).append("</p>");
            Object detail = m.get("detail");
            if (detail != null && !String.valueOf(detail).isBlank()) {
                sb.append("<p class=\"exp-detail\">").append(esc(String.valueOf(detail))).append("</p>");
            }
            sb.append("</div>");
        }
        return sb.toString();
    }

    /** 匹配项 / 差距项：[{skill, evidence}] */
    private static String matchedHtml(Object o, boolean hit) {
        if (!(o instanceof List<?> list) || list.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (Object it : list) {
            String skill;
            String ev = "";
            if (it instanceof Map<?, ?> m) {
                skill = m.get("skill") == null ? humanize(it) : String.valueOf(m.get("skill"));
                ev = m.get("evidence") == null ? "" : String.valueOf(m.get("evidence"));
            } else {
                skill = String.valueOf(it);
            }
            sb.append("<div class=\"m ").append(hit ? "hit" : "gap").append("\">");
            sb.append("<span class=\"mk\">").append(hit ? "命中" : "缺口").append("</span>");
            sb.append("<div><p class=\"msk\">").append(esc(skill)).append("</p>");
            if (!ev.isBlank()) {
                sb.append("<p class=\"mev\">").append(esc(ev)).append("</p>");
            }
            sb.append("</div></div>");
        }
        return sb.toString();
    }

    /**
     * 教育背景：固定四列「学校 / 专业 / 学历 / 学年」。
     * 数据侧已经由 {@link #normalizeEducation} 收成固定契约，这里不再猜键名，只负责渲染。
     */
    private static String educationTable(Object o) {
        List<?> list;
        if (o instanceof List<?> l) {
            list = l;
        } else if (o instanceof Map<?, ?>) {
            list = List.of((Map<?, ?>) o);
        } else {
            return "";
        }
        StringBuilder rows = new StringBuilder();
        for (Object it : list) {
            if (!(it instanceof Map<?, ?> m)) continue;
            String school = firstOf(m, "school");
            String major = firstOf(m, "major");
            String degree = firstOf(m, "degree");
            String year = firstOf(m, "year");
            if (school.isBlank() && major.isBlank() && degree.isBlank() && year.isBlank()) continue;
            rows.append("<tr><td>").append(esc(blankTo(school)))
                    .append("</td><td>").append(esc(blankTo(major)))
                    .append("</td><td>").append(esc(blankTo(degree)))
                    .append("</td><td>").append(esc(blankTo(year))).append("</td></tr>");
        }
        if (rows.length() == 0) return "";
        return "<table class=\"edu\"><thead><tr><th>学校</th><th>专业</th><th>学历</th><th>学年</th></tr></thead>"
                + "<tbody>" + rows + "</tbody></table>";
    }

    /** 按候选键名取值，取不到空串 */
    private static String firstOf(Map<?, ?> m, String... keys) {
        for (String k : keys) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!String.valueOf(e.getKey()).trim().equalsIgnoreCase(k)) continue;
                String v = e.getValue() == null ? "" : String.valueOf(e.getValue()).trim();
                if (!v.isBlank()) return v;
            }
        }
        return "";
    }

    private static String blankTo(String s) {
        return s == null || s.isBlank() ? "—" : s;
    }

    private static String kpi(String k, String v, String tone) {
        String cls = tone == null || tone.isBlank() ? "kpi" : "kpi " + tone;
        return "<div class=\"" + cls + "\"><div class=\"k\">" + esc(k) + "</div><div class=\"v\">" + esc(v)
                + "</div></div>";
    }

    private static String sec(String label, String title) {
        return "<p class=\"eyebrow\">" + esc(label) + "</p><h2>" + esc(title) + "</h2>";
    }

    private static String chips(List<String> items, boolean blue) {
        StringBuilder sb = new StringBuilder("<div class=\"chips\">");
        String cls = blue ? "chip blue" : "chip";
        for (String item : items) {
            sb.append("<span class=\"").append(cls).append("\">")
                    .append(esc(Texts.stripTail(item))).append("</span>");
        }
        return sb.append("</div>").toString();
    }

    private static List<String> stringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object it : list) {
                if (it != null && !String.valueOf(it).isBlank()) out.add(String.valueOf(it));
            }
        } else if (o != null && !String.valueOf(o).isBlank()) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    private static String join(String sep, Object... parts) {
        StringBuilder sb = new StringBuilder();
        for (Object p : parts) {
            if (p == null) continue;
            String s = String.valueOf(p).trim();
            if (s.isEmpty() || "null".equals(s)) continue;
            if (sb.length() > 0) sb.append(sep);
            sb.append(s);
        }
        return sb.toString();
    }

    private static int scoreOf(Map<String, Object> r) {
        Object s = r == null ? null : r.get("score");
        if (s instanceof Number n) return n.intValue();
        try {
            return (int) Math.round(Double.parseDouble(String.valueOf(s)));
        } catch (Exception e) {
            return 0;
        }
    }

    // 说明：原 validateVerdict / validateScore 已删除。
    // 现在 score 与 verdict 由 applyScoring 计算得出，天然合法，不需要再校验模型给的原始值。

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
        out.put("education", normalizeEducation(src != null ? src.get("education") : null));
        // 期望薪资：简历里写明的原文（没有就空串）。它是 offer 阶段出价的锚点，
        // 在 B 阶段顺手抽出来比到 E 阶段再让模型猜可靠得多。
        out.put("expected_salary", normalizeExpectedSalary(src));
        return out;
    }

    /** 期望薪资：优先取模型给的字段，认不出的键名（期望薪资/薪资要求/薪资期望…）也兜一下 */
    private static String normalizeExpectedSalary(Map<String, Object> src) {
        if (src == null) return "";
        for (String key : List.of("expected_salary", "expectedSalary", "salary_expectation", "expectation",
                "期望薪资", "薪资期望", "期望月薪", "薪资要求", "待遇要求")) {
            Object v = src.get(key);
            String s = String.valueOf(v == null ? "" : v).trim();
            if (!s.isEmpty() && !"null".equals(s)) return s;
        }
        return "";
    }

    /**
     * 教育背景规范化：**强制只保留四个字段** {school, major, degree, year}。
     *
     * <p>模型返回的键名很随便（school/university/学校、period/time/duration/时间…），
     * 直接传给前端就得让前端去猜，不同候选人还会出现不同列——这里统一成固定契约：
     * <ul>
     *   <li>{@code school} 学校、{@code major} 专业、{@code degree} 学历、{@code year} 学年（如 2020-2024）</li>
     *   <li>认不出的键一律丢掉；整条都空的行也丢掉</li>
     *   <li>字符串形态（契约允许 education 是字符串，如「本科 统计学」）兜底成一行，
     *       用最朴素的规则拆：含「本/硕/博/专/高中」的片段当学历，含数字年份的当学年，其余按 学校/专业 依次填</li>
     * </ul>
     */
    private static List<Map<String, Object>> normalizeEducation(Object edu) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> items = new ArrayList<>();
        if (edu instanceof List<?> list) {
            items.addAll(list);
        } else if (edu != null) {
            items.add(edu);
        }
        for (Object item : items) {
            if (item == null) continue;
            Map<String, Object> row;
            if (item instanceof Map<?, ?> m) {
                row = new LinkedHashMap<>();
                row.put("school", firstOf(m, "school", "university", "college", "institution", "学校", "院校"));
                row.put("major", firstOf(m, "major", "field", "specialty", "subject", "专业"));
                row.put("degree", firstOf(m, "degree", "level", "education", "学历", "学位"));
                row.put("year", firstOf(m, "year", "period", "time", "duration", "date", "学年", "时间", "起止时间"));
                // 学年可能被拆成 start/end 两个字段
                if (row.get("year").toString().isBlank()) {
                    String start = firstOf(m, "start", "from", "begin", "入学时间", "开始");
                    String end = firstOf(m, "end", "to", "graduation", "毕业时间", "结束");
                    if (!start.isBlank() || !end.isBlank()) {
                        row.put("year", (start.isBlank() ? "" : start) + (start.isBlank() || end.isBlank() ? "" : "-") + end);
                    }
                }
            } else {
                row = parseEducationText(String.valueOf(item));
            }
            // 四个字段全空的行不要
            boolean empty = row.values().stream().allMatch((v) -> String.valueOf(v).isBlank());
            if (empty) continue;
            out.add(row);
        }
        return out;
    }

    /** 字符串形态的教育背景兜底：只做最朴素的拆分，拆不出来就整句放进学校一栏 */
    private static Map<String, Object> parseEducationText(String raw) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("school", "");
        row.put("major", "");
        row.put("degree", "");
        row.put("year", "");
        String text = raw == null ? "" : raw.trim();
        if (text.isEmpty()) return row;

        // 学年：2018-2022 / 2018.09-2022.06 / 2018—2022
        java.util.regex.Matcher ym = java.util.regex.Pattern
                .compile("(\\d{4}\\s*[.年]?\\s*\\d{0,2}\\s*[-—~至]\\s*\\d{4}\\s*[.年]?\\s*\\d{0,2})")
                .matcher(text);
        if (ym.find()) {
            row.put("year", ym.group(1).replaceAll("\\s+", ""));
            text = text.replace(ym.group(1), " ");
        } else {
            java.util.regex.Matcher y1 = java.util.regex.Pattern.compile("(\\d{4})\\s*年?").matcher(text);
            if (y1.find()) {
                row.put("year", y1.group(1));
                text = text.replace(y1.group(1), " ");
            }
        }
        // 学历
        for (String d : List.of("博士", "硕士", "本科", "大专", "专科", "高中", "中专", "MBA")) {
            if (text.contains(d)) {
                row.put("degree", d);
                text = text.replace(d, " ");
                break;
            }
        }
        // 剩下按空格/顿号/斜杠切：带「大学/学院/学校/中学」的那段是学校，另一段是专业。
        // 不能简单按位置取（模型写「统计学 浙江师范大学」这种顺序也很常见，会判反）
        List<String> rest = new ArrayList<>();
        for (String seg : text.split("[\\s、,，/·]+")) {
            String t = seg.trim();
            if (!t.isEmpty()) rest.add(t);
        }
        List<String> schools = new ArrayList<>();
        List<String> others = new ArrayList<>();
        for (String s : rest) {
            if (SCHOOL_HINTS.stream().anyMatch(s::contains)) schools.add(s);
            else others.add(s);
        }
        if (!schools.isEmpty()) {
            row.put("school", schools.get(0));
            if (!others.isEmpty()) row.put("major", others.get(0));
        } else {
            if (!rest.isEmpty()) row.put("school", rest.get(0));
            if (rest.size() > 1) row.put("major", rest.get(1));
        }
        return row;
    }

    /** 判断一段文字是不是学校名：命中这些词就不用靠位置猜了 */
    private static final List<String> SCHOOL_HINTS =
            List.of("大学", "学院", "学校", "中学", "高中", "职院", "职业技术学院");

    /**
     * 自测/演示：用一份内置样例 JD 批量筛选模拟简历，提取合格简历。
     *
     * <p>早前这里默认读一个**别人的绝对路径**（{@code D:/毕业晚会节目/模拟测试A传出.json}，还是 GBK 编码），
     * 换台机器、或那个目录一挪就当场报错。改成内置样例 JD：不依赖任何外部文件，
     * 需要接真实 JD 时把路径当参数传进来即可（{@code main <jd.json>}）。
     */
    public static void main(String[] args) throws Exception {
        Map<String, Object> jd;
        if (args.length > 0) {
            String jdJson = new String(Files.readAllBytes(Path.of(args[0])), Charset.forName("GBK"));
            jd = MAPPER.readValue(jdJson, new TypeReference<Map<String, Object>>() {});
        } else {
            jd = new LinkedHashMap<>();
            jd.put("jd_id", "JD-DEMO-01");
            jd.put("title", "数据分析师");
            jd.put("responsibilities", List.of("搭建业务数据报表", "独立完成数据分析报告"));
            jd.put("hard_requirements", List.of("本科及以上", "3 年以上数据分析经验", "熟悉 SQL 与 Python"));
            jd.put("nice_to_have", List.of("熟悉 Tableau 或 PowerBI", "有自动化报表经验"));
        }

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
        int hireCount = 2;
        List<Map<String, Object>> decisions = ScoutAgent.select(results, hireCount);
        agent.writeReports(jd, results, decisions, hireCount);

        System.out.println("=== 录用决策（计划录用 " + hireCount + " 人）===");
        for (Map<String, Object> d : decisions) {
            System.out.println((Boolean.TRUE.equals(d.get("selected")) ? "录用  " : "未通过")
                    + " 第" + d.get("rank") + "名  " + d.get("name")
                    + "  " + d.get("score") + " 分  " + d.get("rejectReason"));
        }
        System.out.println("已写入 demo/简历初筛报告.html 与各落选者的 未通过通知-姓名.html");
    }
}

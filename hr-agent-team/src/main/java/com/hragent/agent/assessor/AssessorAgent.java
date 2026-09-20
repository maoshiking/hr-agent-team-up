package com.hragent.agent.assessor;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.common.Scores;
import com.hragent.common.Texts;
import com.hragent.tool.AgentTools;
import static com.hragent.common.Jsons.esc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 测评背调员（成员 D 专属）· 技能 assess.review · 文件 agent/assessor/AssessorAgent.java
 *
 * 职责：对候选人做 技能测评 + 真实性核查 + 文化/团队匹配。上游 C，下游 E(offer 管家)。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 校验 → 🔧 doc_writer 落美观报告 → 返回 JSON。
 */
public class AssessorAgent implements Agent {

    private static final String ROLE =
            "你是测评背调员，招聘流程第四步的数字员工。\n" +
            "输入字段：jd、resume、minutes（面试纪要）、能力标准表（招聘分析师给出的每条核心能力及要求强度）、可加 team_profile。\n" +
            "职责：对候选人做技能测评、经历真实性核查、文化/团队匹配。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - level: 只能 expert / proficient / basic / none\n" +
            "  - score: 0-100\n" +
            "  - risk_level: 只能 low / medium / high\n" +
            "  - findings: [{item, risk, evidence}]（真实性核查发现，每项必须带 evidence）\n" +
            "  - fit_score: 0-100、fills_gap: []、overlaps: []\n" +
            "  - competency_checks: 对「能力标准表」逐条打分，{no, actual, evidence}：\n" +
            "      no 就是标准表里的编号，**表里有几条就必须回几条，一条都不许漏、不许合并、不许自己加**；\n" +
            "      actual 是候选人在这条能力上的**实际水平**，0-100 的整数（与标准表的 required 同一把尺子）；\n" +
            "      不要都给同一分数、不要都给整十，要按简历与面试材料如实拉开差距；\n" +
            "      材料不足以判断该条时给一个保守分并在 evidence 里说明缺什么，禁止编造；\n" +
            "      evidence 必须引用简历或面试纪要里的具体依据。\n" +
            "  - reason: 完整思考过程，详细说明测评判断、每项 findings 的核查推理、**逐条对比能力标准表与实际水平的理由**、fit_score 给分依据与风险权衡\n" +
            "全部文字必须用中文书写，禁止输出英文字段名或 JSON 路径（如 resume.experiences[2].detail、minutes.qa[0].answer），\n" +
            "需要指代材料位置时用中文描述，例如「简历第 3 段工作经历」「面试纪要第 1 条问答」。\n" +
            "reason 要详尽展开思考过程（不要只写四段式简句）；禁止编造证据。\n" +
            "本 agent 会使用 doc_writer 工具将成果落成文件。";

    private static final String STYLE = """
            :root{--g:#3f9474;--b:#3f8dc2;--gs:#e9f6f0;--bs:#e9f3fb;--ink:#2f4048;--mut:#74878f;--line:#e7efed}
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;color:var(--ink);line-height:1.65;background:linear-gradient(165deg,#e5f6ee,#e8f2fb 46%,#f0f7fc);background-attachment:fixed;padding:24px 14px;-webkit-font-smoothing:antialiased}
            .page{position:relative;max-width:820px;margin:0 auto;background:#fff;border-radius:18px;box-shadow:0 12px 34px rgba(60,120,120,.14);overflow:hidden}
            .hero{background:linear-gradient(135deg,#3f9474,#63b896 30%,#4f9fd0 72%,#3f8dc2);color:#fff;padding:30px 42px 24px}
            .hero .crumb{font-size:11.5px;letter-spacing:2px;opacity:.9;margin-bottom:8px}
            .hero h1{font-size:26px;font-weight:700;line-height:1.3}
            .hero .sub{font-size:13.5px;opacity:.95;margin-top:8px}
            .body{padding:22px 42px 26px}
            .eyebrow{font-size:10.5px;letter-spacing:2px;color:var(--mut);font-weight:600;margin:22px 0 2px}
            h2{display:flex;align-items:center;gap:9px;font-size:17px;font-weight:700}
            h2::before{content:"";width:4px;height:16px;border-radius:2px;flex:0 0 auto;background:linear-gradient(180deg,var(--g),var(--b))}
            .card{border:1px solid var(--line);border-radius:12px;background:#fff;padding:14px 18px;margin-top:8px}
            .grid4{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin-top:8px}
            .kpi{background:var(--gs);border:1px solid #d5eadf;border-radius:12px;padding:12px;text-align:center}
            .kpi.blue{background:var(--bs);border-color:#d2e5f3}
            .kpi.warn{background:#fdf3e3;border-color:#f0dcb6}
            .kpi.risk{background:#fbeaea;border-color:#f0cccc}
            .kpi .k{font-size:11.5px;color:var(--mut)}
            .kpi .v{font-size:17px;font-weight:700;color:#2c7359;margin-top:3px}
            .kpi.blue .v{color:#2c6392}
            .kpi.warn .v{color:#a8722c}
            .kpi.risk .v{color:#b04a4a}
            .find{margin-top:6px}
            .find .fitem{padding:10px 14px;border:1px solid var(--line);border-radius:10px;margin-bottom:8px;background:#fbfdfc}
            .find .fitem .t{font-weight:600;font-size:13.5px}
            .find .fitem .r{font-size:12.5px;color:#a8722c;margin-top:2px}
            .find .fitem .e{font-size:12.5px;color:var(--mut);margin-top:2px}
            .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}
            .chip{display:inline-flex;padding:3px 11px;border-radius:999px;font-size:12.5px;background:var(--gs);color:#2c7359;border:1px solid #cfe9dc}
            .chip.blue{background:var(--bs);color:#2c6392;border-color:#d2e5f3}
            /* 能力对比：一行一条，标准与本人各一条能量条，长度差异一眼可见 */
            .cmp{margin-top:6px;border:1px solid var(--line);border-radius:12px;background:#fbfdfc;padding:10px 16px 14px}
            .cmp-legend{display:flex;justify-content:space-between;font-size:10.5px;color:#9aa8ad;letter-spacing:.4px;padding:2px 0 6px}
            .cmp-row{padding:9px 0;border-top:1px dashed #e3efea}
            .cmp-row:first-of-type{border-top:0}
            .cmp-head{display:flex;align-items:baseline;gap:8px;flex-wrap:wrap}
            .cmp-no{display:inline-flex;align-items:center;justify-content:center;width:18px;height:18px;border-radius:50%;
                  background:var(--gs);color:#2c7359;font-size:10.5px;font-weight:700}
            .cmp-name{font-size:13.5px;font-weight:600;color:#33525c}
            .cmp-v{font-size:11.5px;font-weight:700;color:#5f7d6c;margin-left:auto}
            .cmp-v.ok{color:#2c7359}
            .cmp-v.warn{color:#a8722c}
            .cmp-v.bad{color:#b04a4a}
            .cmp-gap{font-size:11px;color:#9aa8ad}
            .cmp-bars{display:flex;align-items:center;gap:9px;padding-top:5px}
            .cmp-tag{flex:0 0 32px;font-size:11px;font-weight:600;color:#7c8f96}
            .cmp-track{flex:1 1 150px;min-width:100px;height:8px;border-radius:999px;background:rgba(63,148,116,.13);overflow:hidden}
            .cmp-fill{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#3f9474,#5eb37e)}
            .cmp-fill.std{background:linear-gradient(90deg,#9bb8a8,#bfd3c7)}
            .cmp-fill.real.ok{background:linear-gradient(90deg,#3f9474,#5eb37e)}
            .cmp-fill.real.warn{background:linear-gradient(90deg,#c9a03f,#dcc072)}
            .cmp-fill.real.bad{background:linear-gradient(90deg,#c4656b,#d99a9e)}
            .cmp-num{flex:0 0 26px;text-align:right;font-size:12.5px;font-weight:700;color:#2c7359}
            .cmp-lv{flex:0 0 34px;font-size:11px;color:#8ba394}
            .cmp-ev{font-size:12px;color:#7c8f96;line-height:1.7;padding:4px 0 0 41px}
            .reason{white-space:pre-line;font-size:13px;color:#5c6f78;line-height:1.85;background:#f6faf9;border:1px dashed #d5e6df;border-radius:10px;padding:12px 16px;margin-top:8px}
            footer{padding:14px 42px 24px;text-align:center;color:#94a6ad;font-size:11.5px;letter-spacing:.5px}
            @media(max-width:640px){.grid4{grid-template-columns:1fr 1fr}.hero{padding:22px 20px 18px}.body{padding:16px 18px 18px}}
            """;

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            // 「能力标准表」来自招聘分析师：由**代码**编号后交给模型，模型只回 {no, actual, evidence}，
            // 保证对比表的两侧能按编号严丝合缝对上，也不会被模型改写能力名或要求强度。
            List<Map<String, Object>> standard = standardList(payload);
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + payload + standardHint(standard);
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 校验枚举（忽略大小写）；findings 证据字段宽容处理，不再整体失败
            String level = String.valueOf(result.get("level")).toLowerCase();
            if (!Arrays.asList("expert", "proficient", "basic", "none").contains(level)) {
                throw new IllegalArgumentException("level 必须为 expert/proficient/basic/none");
            }
            String riskLevel = String.valueOf(result.get("risk_level")).toLowerCase();
            if (!Arrays.asList("low", "medium", "high").contains(riskLevel)) {
                throw new IllegalArgumentException("risk_level 必须为 low/medium/high");
            }
            // fit_score 要夹进 0-100：它会直接进综合分（没做题库化面试时面试分就取它），
            // 模型偶尔回个 950 这种数，整个排名就失真了。认不出的当 0，跟别处的口径一致。
            Object rawFit = result.get("fit_score");
            int fit = 0;
            if (rawFit instanceof Number n) fit = n.intValue();
            else {
                try {
                    fit = (int) Math.round(Double.parseDouble(String.valueOf(rawFit).trim()));
                } catch (Exception ignored) {
                    fit = 0;
                }
            }
            result.put("fit_score", Scores.clamp(fit, 0, 100));

            // 标准 × 实际 的能力对比表（哪些满足、哪些不及格）
            // 漏条会让对比表缺行，缺了就用同一份输入补问一次（只补空缺，不覆盖已给的判定）
            List<Map<String, Object>> cmp = compare(standard, result.get("competency_checks"));
            List<Integer> missing = missingNos(cmp);
            if (!cmp.isEmpty() && !missing.isEmpty()) {
                Map<String, Object> retry = client.callJson(ROLE,
                        userInput + "\n注意：你上一次的回复漏了编号 " + missing + " 的 competency_checks。"
                                + "请只输出这些编号（{ no, actual, evidence }），其余字段留空即可。");
                cmp = compare(standard, result.get("competency_checks"), retry.get("competency_checks"));
            }
            if (!cmp.isEmpty()) {
                result.put("competencyChecks", cmp);
                result.put("competencyMet", cmp.stream().filter(c -> "met".equals(c.get("verdict"))).count());
                result.put("competencyFail", cmp.stream().filter(c -> "unmet".equals(c.get("verdict"))).count());
            }
            // competency_checks 是给模型的**对内协议字段**（只有 no/actual/evidence），
            // 成品是 competencyChecks（带能力名、标准值、档位、结论）。
            // 协议字段必须删掉：留着它前端会渲染出「残缺的一份 + 完整的一份」两张对比表。
            result.remove("competency_checks");

            // 🔧 真调用 doc_writer：把测评结果落成美观报告（多候选人时文件名带姓名）
            AgentTools.writeDoc("html",
                    "测评背调报告" + AgentTools.nameSuffix(payload.get("resume")) + ".html",
                    buildHtml(result, payload));

            return result;
        } catch (Exception e) {
            throw new RuntimeException("AssessorAgent 执行失败", e);
        }
    }

    /** 取出 A 的能力标准表：payload 里可能是 persona 整体，也可能直接是列表 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> standardList(Map<String, Object> payload) {
        Object raw = payload == null ? null : payload.get("能力标准表");
        if (raw == null && payload != null && payload.get("persona") instanceof Map<?, ?> p) {
            raw = p.get("core_competencies");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        if (!(raw instanceof List<?> list)) return out;
        for (Object o : list) {
            if (!(o instanceof Map<?, ?> m)) continue;
            String name = m.get("name") == null ? "" : String.valueOf(m.get("name")).trim();
            if (name.isEmpty()) continue;
            Integer required = intOrNull(m.get("required"));
            if (required == null) required = intOrNull(m.get("weight"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("no", out.size() + 1);
            row.put("name", name);
            row.put("required", required == null ? 60 : required);
            row.put("requiredLabel", Scores.levelOf(required == null ? 60 : required));
            out.add(row);
        }
        return out;
    }

    /** 把标准表编号后附加到输入末尾，模型照着编号回执即可 */
    private static String standardHint(List<Map<String, Object>> standard) {
        if (standard.isEmpty()) return "";
        StringBuilder sb = new StringBuilder("\n\n能力标准表（招聘分析师产出，逐条回 competency_checks）：\n");
        for (Map<String, Object> s : standard) {
            sb.append(s.get("no")).append(". ").append(s.get("name"))
                    .append("（要求强度 ").append(s.get("required"))
                    .append("，").append(s.get("requiredLabel")).append("）\n");
        }
        return sb.toString();
    }

    /** 对比结论：满足 / 接近要求 / 不及格 / 未判定 */
    private static final Map<String, String> VERDICT_CN =
            Map.of("met", "满足", "near", "接近要求", "unmet", "不及格", "unjudged", "未判定");

    /**
     * 标准 × 实际：一行一条能力，左边是岗位要求、右边是候选人实际。
     * 判定口径：actual ≥ required → 满足；差 12 分以内 → 接近要求；再低 → 不及格。
     * 多个回执按顺序合并，先到的优先（补问不覆盖已给的判定）。
     */
    @SafeVarargs
    private static List<Map<String, Object>> compare(List<Map<String, Object>> standard, Object... modelReplies) {
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
        for (Map<String, Object> s : standard) {
            int no = (Integer) s.get("no");
            int required = (Integer) s.get("required");
            Map<?, ?> m = given.get(no);
            Integer actual = m == null ? null : intOrNull(m.get("actual"));
            Map<String, Object> row = new LinkedHashMap<>(s);
            row.put("actualRaw", actual == null ? 0 : actual);
            row.put("actual", actual == null ? 0 : actual);
            row.put("gap", actual == null ? 0 : actual - required);
            row.put("noReply", m == null);
            row.put("evidence", m == null || m.get("evidence") == null ? "" : String.valueOf(m.get("evidence")));
            rows.add(row);
        }
        // 本人这一列也拉阶梯：模型常给出一串挤在一起的分数，画出来几条能量条一样长
        spreadActual(rows);
        // 判定必须在分数定稿之后算，保证「显示的数字」和「结论」对得上
        for (Map<String, Object> row : rows) {
            if (Boolean.TRUE.equals(row.get("noReply"))) {
                row.put("actualLabel", "");
                row.put("gap", 0);
                row.put("verdict", "unjudged");
                row.put("verdictLabel", VERDICT_CN.get("unjudged"));
                continue;
            }
            int required = (Integer) row.get("required");
            int actual = (Integer) row.get("actual");
            row.put("actualLabel", Scores.levelOf(actual));
            row.put("gap", actual - required);
            String verdict;
            if (actual >= required) {
                verdict = "met";
            } else if (required - actual <= 12) {
                verdict = "near";
            } else {
                verdict = "unmet";
            }
            row.put("verdict", verdict);
            row.put("verdictLabel", VERDICT_CN.get(verdict));
        }
        return rows;
    }

    /** 把本人实际水平这一列拉开间距（含完全同分的情况），原始分保留在 actualRaw 里 */
    private static void spreadActual(List<Map<String, Object>> rows) {
        if (rows.size() < 2) return;
        int n = rows.size();
        int[] raw = new int[n];
        for (int i = 0; i < n; i++) raw[i] = (Integer) rows.get(i).get("actual");
        int[] spread = Scores.spread(raw, Scores.STD_GAP, Scores.ACT_FLOOR, Scores.ACT_CEIL);
        boolean[] moved = Scores.adjusted(raw, spread);
        for (int i = 0; i < n; i++) {
            Map<String, Object> row = rows.get(i);
            row.put("actual", spread[i]);
            if (moved[i]) row.put("adjusted", true);
        }
    }

    /** 模型没给回执的能力编号 */
    private static List<Integer> missingNos(List<Map<String, Object>> rows) {
        List<Integer> out = new ArrayList<>();
        for (Map<String, Object> c : rows) {
            if (!"unjudged".equals(c.get("verdict"))) continue;
            Object no = c.get("no");
            if (no instanceof Integer i) out.add(i);
        }
        return out;
    }

    /** 数字兜底解析：模型可能给 "72"、72.0、"72 分" */
    private static Integer intOrNull(Object o) {
        if (o instanceof Number n) return (int) Math.round(n.doubleValue());
        if (o == null) return null;
        String s = String.valueOf(o).replaceAll("[^0-9.\\-]", "");
        if (s.isEmpty()) return null;
        try {
            return (int) Math.round(Double.parseDouble(s));
        } catch (RuntimeException e) {
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildHtml(Map<String, Object> result, Map<String, Object> payload) {
        String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String name = "候选人";
        if (payload != null && payload.get("resume") instanceof Map<?, ?> r && r.get("name") != null) {
            name = String.valueOf(r.get("name"));
        }
        String level = levelCn(result.get("level"));
        String risk = riskCn(result.get("risk_level"));

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>候选人测评报告</title>");
        sb.append("<style>").append(STYLE).append("</style></head><body><div class=\"page\">");

        sb.append("<header class=\"hero\"><div class=\"crumb\">测评背调 · ASSESSMENT</div>");
        sb.append("<h1>").append(esc(name)).append(" · 测评与背调报告</h1>");
        sb.append("<div class=\"sub\">技能测评 · 真实性核查 · 文化匹配　·　").append(time).append("</div></header><div class=\"body\">");

        // 核心指标
        sb.append("<div class=\"grid4\">");
        sb.append(kpi("能力等级", level, "level"));
        sb.append(kpi("技能评分", String.valueOf(result.get("score")), "score"));
        sb.append(kpi("风险等级", risk, "risk"));
        sb.append(kpi("文化匹配", String.valueOf(result.get("fit_score")), "fit"));
        sb.append("</div>");

        // 真实性核查发现
        sb.append(sec("核查", "真实性核查发现"));
        sb.append("<div class=\"find\">");
        Object findings = result.get("findings");
        if (findings instanceof List && !((List<?>) findings).isEmpty()) {
            for (Object f : (List<?>) findings) {
                Map<?, ?> fm = (Map<?, ?>) f;
                sb.append("<div class=\"fitem\"><div class=\"t\">").append(esc(String.valueOf(fm.get("item")))).append("</div>");
                sb.append("<div class=\"r\">风险：").append(esc(riskCn(fm.get("risk")))).append("</div>");
                sb.append("<div class=\"e\">证据：").append(esc(String.valueOf(fm.get("evidence")))).append("</div></div>");
            }
        } else {
            sb.append("<div class=\"card\">无异常发现，简历与面试信息一致。</div>");
        }
        sb.append("</div>");

        // 标准 × 实际：能力对比（招聘分析师的标准表 vs 本人的实际水平）
        Object cmpObj = result.get("competencyChecks");
        if (cmpObj instanceof List<?> cmpList && !cmpList.isEmpty()) {
            long met = cmpList.stream().filter((o) -> o instanceof Map<?, ?> m && "met".equals(m.get("verdict"))).count();
            long missed = cmpList.stream().filter((o) -> o instanceof Map<?, ?> m && "unmet".equals(m.get("verdict"))).count();
            sb.append(sec("对比", "能力标准表 × 本人实际水平"));
            sb.append("<div class=\"cmp\">");
            sb.append("<div class=\"cmp-legend\"><span>岗位要求强度（招聘分析师的标准表）</span>")
                    .append("<span>本人实际水平（测评背调）</span></div>");
            for (Object o : cmpList) {
                if (!(o instanceof Map<?, ?> c)) continue;
                int required = c.get("required") instanceof Integer ? (Integer) c.get("required") : 0;
                int actual = c.get("actual") instanceof Integer ? (Integer) c.get("actual") : 0;
                String verdict = String.valueOf(c.get("verdict"));
                String vcls = "met".equals(verdict) ? " ok" : ("unmet".equals(verdict) ? " bad"
                        : ("near".equals(verdict) ? " warn" : ""));
                Integer gap = c.get("gap") instanceof Integer ? (Integer) c.get("gap") : null;
                sb.append("<div class=\"cmp-row\">")
                        .append("<div class=\"cmp-head\"><span class=\"cmp-no\">").append(c.get("no")).append("</span>")
                        .append("<span class=\"cmp-name\">").append(esc(String.valueOf(c.get("name")))).append("</span>")
                        .append("<span class=\"cmp-v").append(vcls).append("\">")
                        .append(esc(String.valueOf(c.get("verdictLabel")))).append("</span>")
                        .append(gap != null && gap != 0
                                ? "<span class=\"cmp-gap\">" + (gap > 0 ? "高于要求 " : "低于要求 ") + Math.abs(gap) + " 分</span>"
                                : "")
                        .append("</div>")
                        .append("<div class=\"cmp-bars\">")
                        .append("<span class=\"cmp-tag\">标准</span>")
                        .append("<span class=\"cmp-track\"><i class=\"cmp-fill std\" style=\"width:")
                        .append(required).append("%\"></i></span>")
                        .append("<span class=\"cmp-num\">").append(required).append("</span>")
                        .append("<span class=\"cmp-lv\">").append(esc(String.valueOf(c.get("requiredLabel")))).append("</span>")
                        .append("</div>")
                        .append("<div class=\"cmp-bars\">")
                        .append("<span class=\"cmp-tag\">本人</span>")
                        .append("<span class=\"cmp-track\"><i class=\"cmp-fill real ").append(vcls.trim())
                        .append("\" style=\"width:").append(actual).append("%\"></i></span>")
                        .append("<span class=\"cmp-num\">").append(actual).append("</span>")
                        .append("<span class=\"cmp-lv\">").append(esc(String.valueOf(c.get("actualLabel")))).append("</span>")
                        .append("</div>");
                String cev = String.valueOf(c.get("evidence") == null ? "" : c.get("evidence"));
                if (!cev.isBlank()) {
                    sb.append("<div class=\"cmp-ev\">依据：").append(esc(cev)).append("</div>");
                }
                sb.append("</div>");
            }
            sb.append("</div>");
            sb.append("<div class=\"card\">满足 <b>").append(met).append("</b> 条，不及格 <b>")
                    .append(missed).append("</b> 条（共 ").append(cmpList.size()).append(" 条）。")
                    .append("岗位要求强度来自招聘分析师的能力标准表，本人实际水平由本次测评给出。</div>");
        }

        // 团队匹配
        sb.append(sec("匹配", "团队能力匹配"));
        sb.append("<div class=\"card\"><p style=\"font-size:12px;color:var(--mut);font-weight:600\">可补足的团队缺口</p>");
        sb.append(chips((List<?>) result.get("fills_gap"), false));
        sb.append("<p style=\"font-size:12px;color:var(--mut);font-weight:600;margin-top:10px\">与团队的冗余能力</p>");
        sb.append(chips((List<?>) result.get("overlaps"), true));
        sb.append("</div>");

        // 分析说明已由前端「思考过程」展示，报告里不再重复
        sb.append("</div><footer>测评背调员 · talent.assess 自动生成　·　").append(time).append("</footer>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String sec(String label, String title) {
        return "<p class=\"eyebrow\">" + esc(label) + "</p><h2>" + esc(title) + "</h2>";
    }

    private static String kpi(String k, String v, String tone) {
        String cls = "kpi";
        if ("score".equals(tone) || "fit".equals(tone)) cls = "kpi blue";
        else if ("risk".equals(tone)) cls = "kpi " + ("高风险".equals(v) ? "risk" : "warn");
        return "<div class=\"" + cls + "\"><div class=\"k\">" + esc(k) + "</div><div class=\"v\">" + esc(v) + "</div></div>";
    }

    /** 枚举值 → 中文：报表里不再出现 proficient / medium / high 这类裸英文。 */
    private static String levelCn(Object v) {
        String raw = String.valueOf(v == null ? "" : v);
        String s = raw.toLowerCase();
        if (s.contains("expert")) return "专家级";
        if (s.contains("proficient")) return "熟练级";
        if (s.contains("basic")) return "基础级";
        if (s.contains("none")) return "未具备";
        return raw;
    }

    private static String riskCn(Object v) {
        String raw = String.valueOf(v == null ? "" : v);
        String s = raw.toLowerCase();
        if (s.contains("high") || s.contains("高")) return "高风险";
        if (s.contains("medium") || s.contains("中")) return "中风险";
        if (s.contains("low") || s.contains("低")) return "低风险";
        return raw;
    }

    private static String chips(List<?> items, boolean blue) {
        if (items == null || items.isEmpty()) return "<p style=\"font-size:12.5px;color:var(--mut);margin-top:6px\">— 无 —</p>";
        StringBuilder sb = new StringBuilder("<div class=\"chips\">");
        String cls = blue ? "chip blue" : "chip";
        for (Object item : items) {
            sb.append("<span class=\"").append(cls).append("\">")
                    .append(esc(Texts.stripTail(String.valueOf(item)))).append("</span>");
        }
        sb.append("</div>");
        return sb.toString();
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

package com.hragent.agent.interviewer;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.tool.AgentTools;
import static com.hragent.common.Jsons.esc;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
/**
 * 面试官（成员 C 专属）· 技能 interview.run · 文件 agent/interviewer/InterviewerAgent.java
 *
 * 职责：按 JD+简历生成面试题纲；根据问答记录生成纪要并给结论。上游 B，下游 D(测评背调员)。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 校验 → 🔧 doc_writer 落美观网页 → 返回 JSON。
 */
public class InterviewerAgent implements Agent {

    private static final String ROLE =
            "你是招聘流程第三步的线上面试官。\n" +
            "输入可能包含 industry、promptPack、jd、resume、transcript；promptPack 是本场冻结的行业题库和评分维度，必须优先遵守。\n" +
            "没有非空 transcript 时，只生成 plan：覆盖通用素质题、行业专业题、线上协作情景题和反向提问，并注明时长、考察目标和事实证据。\n" +
            "有非空 transcript 时，只生成 minutes：按问答总结评分，并根据回答中的项目、角色、技术、数字和决策提出至多两次引用式追问建议。\n" +
            "只能输出 JSON：plan{questions[]{text,intent},rubric[]{dimension,weight}} 或 minutes{summary,qa[]{question,answer,score},verdict}，以及 reason。\n" +
            "minutes.qa 的 answer 必须精简概括（每条不超过 60 字），不要照抄原始问答全文，否则 JSON 会因过长被截断。\n" +
            "score 必须为 0-100 整数，verdict 只能 shortlist / hold / reject；证据不足使用 hold。\n" +
            "禁止编造，禁止根据外貌、表情、眼神、情绪、声音、人脸或视频状态评分；视频事件只能触发人工复核。\n" +
            "reason 要详尽展开思考过程（不要只写四段式简句）：说明评分依据、追问选择的理由、shortlist/hold/reject 的权衡；风格：结论→依据→风险→下一步。";

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
            .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:8px}
            .kpi{background:var(--gs);border:1px solid #d5eadf;border-radius:12px;padding:12px;text-align:center}
            .kpi.blue{background:var(--bs);border-color:#d2e5f3}
            .kpi .k{font-size:11.5px;color:var(--mut)}
            .kpi .v{font-size:17px;font-weight:700;color:#2c7359;margin-top:3px}
            .kpi.blue .v{color:#2c6392}
            .rows{font-size:14px}
            .row{display:flex;justify-content:space-between;gap:12px;padding:7px 2px;border-bottom:1px dashed #eef3f1}
            .row:last-child{border-bottom:none}
            .row dt{color:var(--mut);flex:0 0 auto}
            .row dd{color:#33484f;font-weight:500;text-align:right}
            ul.plain{list-style:none}
            ul.plain li{position:relative;padding:4px 0 4px 16px;font-size:13.5px;color:#465a63}
            ul.plain li::before{content:"";position:absolute;left:0;top:11px;width:6px;height:6px;border-radius:2px;background:linear-gradient(135deg,var(--g),var(--b))}
            .h{font-size:12px;color:var(--mut);font-weight:600;margin-bottom:4px}
            .badge{display:inline-block;padding:3px 12px;border-radius:999px;font-size:12.5px;font-weight:600}
            .badge.good{background:var(--gs);color:#2c7359}
            .badge.warn{background:#fdf3e3;color:#a8722c}
            .badge.risk{background:#fbeaea;color:#b04a4a}
            .qa{margin-top:6px;border:1px solid var(--line);border-radius:12px;overflow:hidden}
            .qa .item{padding:10px 16px;border-bottom:1px solid #eef3f1}
            .qa .item:last-child{border-bottom:none}
            .qa .q{font-weight:600;font-size:14px}
            .qa .a{color:#4a5d66;font-size:13.5px;margin-top:3px}
            .qa .s{font-size:12px;color:var(--mut);margin-top:2px}
            .reason{white-space:pre-line;font-size:13px;color:#5c6f78;line-height:1.85;background:#f6faf9;border:1px dashed #d5e6df;border-radius:10px;padding:12px 16px;margin-top:8px}
            footer{padding:14px 42px 24px;text-align:center;color:#94a6ad;font-size:11.5px;letter-spacing:.5px}
            @media(max-width:640px){.grid{grid-template-columns:1fr 1fr}.hero{padding:22px 20px 18px}.body{padding:16px 18px 18px}}
            """;

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        boolean hasTranscript = hasNonBlankTranscript(payload);
        String base = "请根据以下输入完成任务。\n输入 JSON：\n" + payload;
        Map<String, Object> result = null;
        RuntimeException last = null;
        for (int attempt = 0; attempt < 3; attempt++) {
            try {
                String userInput = attempt == 0 ? base
                        : base + "\n注意：必须输出 " + (hasTranscript ? "minutes" : "plan") + " 结构（含 reason），不要输出其它结构。";
                Map<String, Object> candidate = client.callJson(ROLE, userInput);
                validateResult(candidate, hasTranscript);
                result = candidate; // 只有校验通过才采纳，避免非法数据流入 HTML 生成导致 500
                last = null;
                break;
            } catch (RuntimeException ex) {
                last = ex;
            }
        }
        if (result == null) {
            throw new RuntimeException("面试官执行失败（重试后仍未通过校验）："
                    + (last == null ? "未知原因" : last.getMessage()));
        }
        try {
            // 🔧 真调用 doc_writer：把题纲/纪要落成美观网页（多候选人时文件名带姓名，避免互相覆盖）
            AgentTools.writeDoc("html",
                    (hasTranscript ? "面试纪要" : "面试题纲") + AgentTools.nameSuffix(payload.get("resume")) + ".html",
                    buildHtml(result, hasTranscript, payload));
        } catch (Exception e) {
            throw new RuntimeException("InterviewerAgent 落盘失败", e);
        }
        return result;
    }

    private boolean hasNonBlankTranscript(Map<String, Object> payload) {
        Object transcript = payload == null ? null : payload.get("transcript");
        return transcript != null && !String.valueOf(transcript).isBlank();
    }

    /**
     * 把模型回的「纪要」宽容地归位到 {@code minutes}。
     *
     * <p>线上真实翻过车：模型给的 JSON 合法但结构飘了——有的是把 qa/summary/verdict 直接摊在顶层，
     * 有的把 key 写成「纪要」「interview_minutes」。老代码只认 `result.get("minutes")`，
     * 三次重试都拧不回来，整轮面试直接 500（用户看到的「缺少输出字段：minutes」）。
     * 这里按「像不像纪要」来找，找得到就归位，找不到才交给上层（上层也已改成不让这一步拖垮流程）。
     */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> normalizeMinutes(Map<String, Object> result) {
        if (result.get("minutes") instanceof Map<?, ?> direct) return (Map<String, Object>) direct;
        // 情形一：模型把内容摊平在顶层（有 qa 就算）
        if (result.get("qa") instanceof java.util.List<?>) {
            Map<String, Object> flat = new LinkedHashMap<>();
            for (String key : java.util.List.of("summary", "qa", "verdict", "追问建议", "followups")) {
                if (result.containsKey(key)) flat.put(key, result.get(key));
            }
            return flat;
        }
        // 情形二：换了个名字（minutes / 纪要 / interview_minutes / record…），或值里带 qa
        for (Map.Entry<String, Object> e : result.entrySet()) {
            if (!(e.getValue() instanceof Map<?, ?> m)) continue;
            String key = e.getKey().toLowerCase();
            boolean named = key.contains("minute") || key.contains("纪要") || key.contains("record")
                    || key.contains("interview");
            if (named || m.get("qa") instanceof java.util.List<?>) return (Map<String, Object>) m;
        }
        return null;
    }

    /** qa 换了名字（items / 问答 / list）时也认出来，免得纪要有摘要却没有问答 */
    @SuppressWarnings("unchecked")
    private static void normalizeQa(Map<String, Object> minutes) {
        if (minutes.get("qa") instanceof java.util.List<?>) return;
        for (String key : java.util.List.of("items", "问答", "qaList", "list", "records")) {
            if (minutes.get(key) instanceof java.util.List<?> list) {
                minutes.put("qa", list);
                return;
            }
        }
        minutes.put("qa", new java.util.ArrayList<>());
    }

    @SuppressWarnings("unchecked")
    private void validateResult(Map<String, Object> result, boolean minutesMode) {
        if (result == null) throw new IllegalArgumentException("模型返回为空");
        if (String.valueOf(result.getOrDefault("reason", "")).isBlank()) {
            throw new IllegalArgumentException("reason 不能为空");
        }
        String root = minutesMode ? "minutes" : "plan";
        if (minutesMode) {
            Map<String, Object> value = normalizeMinutes(result);
            if (value == null) throw new IllegalArgumentException("缺少输出字段：" + root);
            normalizeQa(value);
            result.put("minutes", value); // 归位：后面渲染与落盘统一用这一份
        } else if (!(result.get(root) instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("缺少输出字段：" + root);
        }
        Map<?, ?> value = (Map<?, ?>) result.get(root);
        if (minutesMode) {
            String verdict = String.valueOf(value.get("verdict")).toLowerCase().trim();
            if (!java.util.List.of("shortlist", "hold", "reject").contains(verdict)) {
                // 宽容归一：把模型用词变体归到三档，避免整轮失败
                String normalized;
                if (verdict.contains("short") || verdict.contains("pass") || verdict.contains("hire")
                        || verdict.contains("通过") || verdict.contains("推荐")) {
                    normalized = "shortlist";
                } else if (verdict.contains("reject") || verdict.contains("fail")
                        || verdict.contains("淘汰") || verdict.contains("不通过")) {
                    normalized = "reject";
                } else {
                    normalized = "hold";
                }
                ((Map<String, Object>) value).put("verdict", normalized);
            }
            // qa 里混进非对象（模型偶尔给出字符串）时只丢那几条，别整轮作废
            if (value.get("qa") instanceof java.util.List<?> qa) {
                java.util.List<Object> kept = new java.util.ArrayList<>();
                for (Object item : qa) {
                    if (item instanceof Map<?, ?>) kept.add(item);
                }
                ((Map<String, Object>) value).put("qa", kept);
            }
            boolean hasQa = value.get("qa") instanceof java.util.List<?> list && !list.isEmpty();
            boolean hasSummary = !String.valueOf(value.get("summary") == null ? "" : value.get("summary")).isBlank();
            if (!hasQa && !hasSummary) {
                throw new IllegalArgumentException("minutes 既没有 qa 也没有 summary");
            }
        } else {
            if (!(value.get("questions") instanceof java.util.List<?> questions) || questions.isEmpty()
                    || !(value.get("rubric") instanceof java.util.List<?> rubric) || rubric.isEmpty()) {
                throw new IllegalArgumentException("plan.questions 和 plan.rubric 必须是数组");
            }
            for (Object question : questions) {
                if (!(question instanceof Map<?, ?> row)
                        || String.valueOf(row.get("text") == null ? "" : row.get("text")).isBlank()
                        || String.valueOf(row.get("intent") == null ? "" : row.get("intent")).isBlank()) {
                    throw new IllegalArgumentException("题目必须包含 text 和 intent");
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String buildHtml(Map<String, Object> result, boolean minutesMode, Map<String, Object> payload) {
        String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String name = "候选人";
        if (payload != null && payload.get("resume") instanceof Map<?, ?> r && r.get("name") != null) {
            name = String.valueOf(r.get("name"));
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>").append(minutesMode ? "面试纪要" : "面试题纲").append("</title>");
        sb.append("<style>").append(STYLE).append("</style></head><body><div class=\"page\">");

        if (minutesMode) {
            Map<String, Object> minutes = (Map<String, Object>) result.get("minutes");
            String verdict = String.valueOf(minutes.get("verdict"));
            sb.append("<header class=\"hero\"><div class=\"crumb\">面试纪要 · INTERVIEW MINUTES</div>");
            sb.append("<h1>").append(esc(name)).append(" · 面试纪要</h1>");
            sb.append("<div class=\"sub\">线上结构化面试　·　").append(time).append("</div></header><div class=\"body\">");

            sb.append("<div class=\"grid\">");
            sb.append(kpi("面试结论", verdictLabel(verdict), false));
            Object qaObj = minutes.get("qa");
            int qaCount = qaObj instanceof java.util.List ? ((java.util.List<?>) qaObj).size() : 0;
            sb.append(kpi("问答条数", String.valueOf(qaCount), true));
            sb.append(kpi("面试时间", time, true));
            sb.append("</div>");

            sb.append(sec("摘要", "面试摘要"));
            sb.append("<div class=\"card\">").append(esc(String.valueOf(minutes.get("summary")))).append("</div>");

            sb.append(sec("问答", "逐题问答与评分"));
            sb.append("<div class=\"qa\">");
            if (minutes.get("qa") instanceof java.util.List<?> qaList) {
                for (Object o : qaList) {
                    if (!(o instanceof Map<?, ?> qa)) continue;
                    sb.append("<div class=\"item\"><div class=\"q\">Q：").append(esc(String.valueOf(qa.get("question")))).append("</div>");
                    sb.append("<div class=\"a\">A：").append(esc(String.valueOf(qa.get("answer")))).append("</div>");
                    sb.append("<div class=\"s\">得分：").append(String.valueOf(qa.get("score"))).append(" / 100</div></div>");
                }
            }
            sb.append("</div>");
        } else {
            Map<String, Object> plan = (Map<String, Object>) result.get("plan");
            sb.append("<header class=\"hero\"><div class=\"crumb\">面试题纲 · INTERVIEW PLAN</div>");
            sb.append("<h1>").append(esc(name)).append(" · 面试题纲</h1>");
            sb.append("<div class=\"sub\">结构化面试题目与评分维度　·　").append(time).append("</div></header><div class=\"body\">");

            sb.append(sec("题目", "面试题目"));
            sb.append("<div class=\"qa\">");
            int i = 1;
            for (Object o : (java.util.List<?>) plan.get("questions")) {
                Map<?, ?> q = (Map<?, ?>) o;
                sb.append("<div class=\"item\"><div class=\"q\">").append(i++).append(". ").append(esc(String.valueOf(q.get("text")))).append("</div>");
                sb.append("<div class=\"s\">考察意图：").append(esc(String.valueOf(q.get("intent")))).append("</div></div>");
            }
            sb.append("</div>");

            sb.append(sec("评分", "评分维度（rubric）"));
            sb.append("<div class=\"card rows\">");
            for (Object o : (java.util.List<?>) plan.get("rubric")) {
                Map<?, ?> rb = (Map<?, ?>) o;
                sb.append("<div class=\"row\"><dt>").append(esc(String.valueOf(rb.get("dimension")))).append("</dt><dd>权重 ").append(String.valueOf(rb.get("weight"))).append("</dd></div>");
            }
            sb.append("</div>");
        }

        // 分析说明已由前端「思考过程」展示，报告里不再重复
        sb.append("</div><footer>面试官 · talent.interview 自动生成　·　").append(time).append("</footer>");
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private static String sec(String label, String title) {
        return "<p class=\"eyebrow\">" + esc(label) + "</p><h2>" + esc(title) + "</h2>";
    }

    private static String kpi(String k, String v, boolean blue) {
        return "<div class=\"kpi" + (blue ? " blue" : "") + "\"><div class=\"k\">" + esc(k) + "</div><div class=\"v\">" + esc(v) + "</div></div>";
    }

    private static String verdictLabel(String v) {
        if ("shortlist".equals(v)) return "shortlist · 进入下一轮";
        if ("reject".equals(v)) return "reject · 淘汰";
        return "hold · 待定/转人工";
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

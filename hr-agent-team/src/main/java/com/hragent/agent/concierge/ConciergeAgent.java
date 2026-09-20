package com.hragent.agent.concierge;

import com.hragent.agent.Agent;
import com.hragent.common.DeepSeekClient;
import com.hragent.common.NoticeHtml;
import com.hragent.tool.AgentTools;
import static com.hragent.common.Jsons.asMap;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * offer 与入职管家（成员 E 专属）· 技能 offer.onboard · 文件 agent/concierge/ConciergeAgent.java
 *
 * 职责：拿 D 的测评结论 → 生成 offer 草案与谈薪建议 → 制定 30 天入职计划（含反哺筛选建议）。
 * 上游 D，无下游（流程收尾）。
 *
 * 开发说明：run() 已接好 读输入 → 调模型 → 校验 → 🔧 doc_writer 落美观 Offer 网页（含印章）→ 返回 JSON。
 */
public class ConciergeAgent implements Agent {

    private static final String ROLE =
            "你是 offer 与入职管家，招聘流程收尾的数字员工。\n" +
            "职责：基于候选人综合结论生成 offer 草案与谈薪建议，并制定 30 天入职计划。\n" +
            "输入字段：候选人、岗位需求、简历、面试纪要、简历评价、测评背调、薪资基准、市场薪资区间、预算总额、录用条件、offer关卡。\n" +
            "重要：**「录用条件」是人工确认过的权威数据（薪酬包与岗位信息）**，你必须以它为准，\n" +
            "  不要改动其中已给出的任何数字或名称；你的职责是①补全它没覆盖的部分 ②写录用条款 ③评估出价风险 ④制定入职计划。\n" +
            "  录用条件为空时才由你按市场基准给区间与建议。\n" +
            "你必须输出一个 JSON，字段：\n" +
            "  - offer: { position, salary_range, suggested, clauses[] }\n" +
            "      salary_range 写市场区间（有「市场薪资区间」就用它）；suggested 写出价建议与风险提示（例如与候选人期望的差距、是否顶到预算上限）\n" +
            "  - negotiation: 谈薪策略（**内部材料，不对外发**），字段：\n" +
            "      { target 目标出价, floor 谈判底线, ceil 可接受上限, acceptanceRisk 候选人接受概率评估（高/中/低 + 一句理由）, \n" +
            "        rationale 这个区间的依据（候选人的期望与议价力量、我们的排名结论、市场区间、测评风险）, \n" +
            "        alternatives[] 给不了钱能给什么（职级/弹性工时/培训预算/期权/汇报关系…）, walkAwayPlan 谈崩预案（先转向谁、如何回旋） }\n" +
            "      数字要与「录用条件」里的月薪口径一致，不许凭空加价；没有依据就写「需人工判断」\n" +
            "  - plan: { week1_4[], goals[], screening_feedback, d30[], d60[], d90[], buddy, training[], tools[], checkpoints[] }\n" +
            "      d30/d60/d90 是入职 30/60/90 天目标；buddy 是导师或伙伴安排；training 是首月培训；\n" +
            "      tools 是首周要开的账号与工具；checkpoints 是检查点（谁在什么时候看什么）\n" +
            "  - reason: 完整思考过程，详细说明定薪依据、条款设计考量、入职计划安排逻辑与风险\n" +
            "薪资给区间并说明依据，禁止编造具体数字；没有薪资基准时输出待人工确认。\n" +
            "提到录用条件里的任何一项时，**一律用中文名称**（例如「基本月薪」「年终奖月数」「试用期」），" +
            "禁止把 baseSalary / jobLevel 这类字段名写进任何文案；\n" +
            "提到其它环节的结论时同样用中文（例如「简历评价 92 分」「结论：进入录用名单」「风险等级：低」），" +
            "禁止写 scorecard / shortlist / risk_level / low 这类字段名或英文取值。";

    private static final String STYLE = """
            :root{--g:#3f9474;--b:#3f8dc2;--gs:#e9f6f0;--bs:#e9f3fb;--ink:#2f4048;--mut:#74878f;--line:#e7efed}
            *{box-sizing:border-box;margin:0;padding:0}
            body{font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;color:var(--ink);line-height:1.65;background:linear-gradient(165deg,#e5f6ee,#e8f2fb 46%,#f0f7fc);background-attachment:fixed;padding:24px 14px;-webkit-font-smoothing:antialiased}
            .page{position:relative;max-width:820px;margin:0 auto;background:#fff;border-radius:18px;box-shadow:0 12px 34px rgba(60,120,120,.14);overflow:hidden}
            .hero{background:linear-gradient(135deg,#3f9474,#63b896 30%,#4f9fd0 72%,#3f8dc2);color:#fff;padding:32px 44px 26px}
            .hero .crumb{font-size:11.5px;letter-spacing:2px;opacity:.9;margin-bottom:8px}
            .hero h1{font-size:28px;font-weight:700;line-height:1.3}
            .hero .sub{font-size:13.5px;opacity:.95;margin-top:10px}
            .body{padding:22px 44px 26px}
            .eyebrow{font-size:10.5px;letter-spacing:2px;color:var(--mut);font-weight:600;margin:22px 0 2px}
            h2{display:flex;align-items:center;gap:9px;font-size:17px;font-weight:700}
            h2::before{content:"";width:4px;height:16px;border-radius:2px;flex:0 0 auto;background:linear-gradient(180deg,var(--g),var(--b))}
            .card{border:1px solid var(--line);border-radius:12px;background:#fff;padding:14px 18px;margin-top:8px}
            .grid{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:8px}
            .kpi{background:var(--gs);border:1px solid #d5eadf;border-radius:12px;padding:12px;text-align:center}
            .kpi.blue{background:var(--bs);border-color:#d2e5f3}
            .kpi .k{font-size:11.5px;color:var(--mut)}
            .kpi .v{font-size:16px;font-weight:700;color:#2c7359;margin-top:3px}
            .kpi.blue .v{color:#2c6392}
            .rows{font-size:14px}
            .row{display:flex;justify-content:space-between;gap:12px;padding:8px 2px;border-bottom:1px dashed #eef3f1}
            .row:last-child{border-bottom:none}
            .row dt{color:var(--mut);flex:0 0 auto}
            .row dd{color:#33484f;font-weight:500;text-align:right}
            ul.plain{list-style:none}
            ul.plain li{position:relative;padding:5px 0 5px 16px;font-size:13.5px;color:#465a63}
            ul.plain li::before{content:"";position:absolute;left:0;top:12px;width:6px;height:6px;border-radius:2px;background:linear-gradient(135deg,var(--g),var(--b))}
            .h{font-size:12px;color:var(--mut);font-weight:600;margin-bottom:4px}
            .salary{font-size:22px;font-weight:700;color:#2c7359;letter-spacing:.5px}
            .reason{white-space:pre-line;font-size:13px;color:#5c6f78;line-height:1.85;background:#f6faf9;border:1px dashed #d5e6df;border-radius:10px;padding:12px 16px;margin-top:8px}
            .note{font-size:12.5px;color:#4a5d66;background:var(--bs);border:1px solid #d2e5f3;border-radius:10px;padding:10px 14px;margin-top:12px}
            .sign{margin-top:30px;text-align:right;color:#33484f}
            .sign .company{font-weight:600;font-size:15px}
            .sign .date{font-size:12.5px;color:var(--mut);margin-top:6px}
            table.tb{width:100%;border-collapse:separate;border-spacing:0;margin-top:8px;font-size:13.5px;border:1px solid var(--line);border-radius:12px;overflow:hidden}
            table.tb th{width:150px;text-align:left;font-weight:600;font-size:12.5px;color:#4a6a60;background:linear-gradient(180deg,#f2fbf7,#eaf4fb);padding:8px 14px;border-bottom:1px solid var(--line)}
            table.tb td{padding:8px 14px;border-bottom:1px solid #f1f6f5;color:#33484f;font-weight:500}
            table.tb tr:last-child th,table.tb tr:last-child td{border-bottom:none}
            table.tb tr:nth-child(even) td{background:#fafcfc}
            footer{padding:14px 44px 26px;color:#94a6ad;font-size:11.5px;letter-spacing:.5px}
            .seal{position:absolute;right:26px;bottom:18px;width:150px;pointer-events:none;z-index:6;transform:rotate(-10deg);opacity:.88;filter:drop-shadow(0 3px 6px rgba(0,0,0,.15))}
            @media(max-width:640px){.grid{grid-template-columns:1fr 1fr}.hero{padding:24px 20px 20px}.body{padding:16px 18px 18px}}
            """;

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    @SuppressWarnings("unchecked")
    public Map<String, Object> run(Map<String, Object> payload) {
        // 多候选人时文件名带姓名，避免互相覆盖；try/catch 两条路径都要用，所以放在外面
        String suffix = AgentTools.nameSuffix(payload == null ? null : payload.get("candidate"));
        try {
            // 喂给模型的输入整体中文化（键名 + 英文字段名 + shortlist/low 这类取值）：
            // 模型很爱在正文里引用输入里的字段名，给英文它就会写「baseSalary=25000/月」「scorecard 92分、shortlist」（用户反馈过）。
            // 只动这份副本，真实数据一个键都不改。
            Map<String, Object> modelInput = localizeInput(payload == null ? Map.of() : payload);
            String userInput = "请根据以下输入完成任务。\n输入 JSON：\n" + modelInput;
            Map<String, Object> result = null;
            RuntimeException last = null;
            // 与其他 agent 一致：模型偶尔结构飘了，重试一次比整份 offer 退化成「待人工确认」划算
            for (int attempt = 0; attempt < 2; attempt++) {
                try {
                    Map<String, Object> candidate = client.callJson(ROLE,
                            attempt == 0 ? userInput
                                    : userInput + "\n注意：必须输出 offer（含 position/salary_range/suggested/clauses）、"
                                            + "negotiation 与 plan（含 week1_4/goals/screening_feedback/d30/d60/d90/buddy/training/tools/checkpoints）以及 reason。");
                    validate(candidate);
                    result = candidate;
                    last = null;
                    break;
                } catch (RuntimeException ex) {
                    last = ex;
                }
            }
            if (result == null) {
                throw new IllegalStateException(last == null ? "模型返回为空" : last.getMessage());
            }
            // 谈薪策略与入职计划的新字段：模型漏了就补空结构，保证报告与页面都能正常渲染
            fillDefaults(result);
            // 兜底：万一它还是写了英文字段名，把文案里的键换成中文再往外发
            localizeResultText(result);

            // 校验 offer 字段
            Object offer = result.get("offer");
            if (!(offer instanceof Map)) {
                throw new IllegalArgumentException("必须输出 offer 对象");
            }
            Map<?, ?> offerMap = (Map<?, ?>) offer;
            if (!offerMap.containsKey("position") || !offerMap.containsKey("salary_range") ||
                    !offerMap.containsKey("suggested") || !offerMap.containsKey("clauses")) {
                throw new IllegalArgumentException("offer 必须包含 position, salary_range, suggested, clauses");
            }
            if (!result.containsKey("plan")) {
                throw new IllegalArgumentException("必须输出 plan");
            }
            // 薪酬明细由**代码**从人工确认的录用条件拼出来（年包估算也在这里算）：
            // 数字以用户填的为准，模型不许改动——这是「录用条件是权威数据」这条口径的落点。
            Map<String, Object> terms = asMap(payload == null ? null : payload.get("录用条件"));
            Map<String, Object> compensation = assembleCompensation(terms);
            if (!compensation.isEmpty()) {
                ((Map<String, Object>) offer).put("compensation", compensation);
                // 岗位名也以录用条件为准（它比模型猜的更准）
                Object pos = terms.get("position");
                if (pos != null && !String.valueOf(pos).isBlank()) {
                    ((Map<String, Object>) offer).put("position", String.valueOf(pos));
                }
            }

            // 🔧 真调用 doc_writer：把 offer/入职计划落成美观网页（右下角盖印章；多候选人时文件名带姓名）
            AgentTools.writeDoc("html", "录用与入职计划" + suffix + ".html", buildOfferHtml(result, payload));

            return result;
        } catch (Exception e) {
            Map<String, Object> offer = new LinkedHashMap<>();
            Map<String, Object> jd = payload == null || !(payload.get("jd") instanceof Map<?, ?>) ? Map.of() : (Map<String, Object>) payload.get("jd");
            offer.put("position", String.valueOf(jd.getOrDefault("title", "待定岗位")));
            offer.put("salary_range", "待人工确认");
            offer.put("suggested", "缺少可靠薪资基准，暂不提供数字建议");
            offer.put("clauses", java.util.List.of());
            Map<String, Object> plan = new LinkedHashMap<>();
            plan.put("week1_4", java.util.List.of("人工确认 Offer 条款后制定入职计划"));
            plan.put("goals", java.util.List.of());
            plan.put("screening_feedback", "自动生成不可用，需人工补充筛选反馈");
            Map<String, Object> fallback = new LinkedHashMap<>();
            fallback.put("offer", offer); fallback.put("plan", plan);
            fallback.put("reason", "结论：Offer 待人工确认；依据：自动生成不可用；风险：薪资基准缺失；下一步：人工确认薪资与条款。");
            try { AgentTools.writeDoc("html", "录用与入职计划" + suffix + ".html", "<!doctype html><meta charset=\"utf-8\"><h1>Offer 待人工确认</h1>"); }
            catch (Exception ignored) { }
            return fallback;
        }
    }

    /**
     * 面试/测评后未通过通知：每位落选者一份。
     * 刻意不列具体差距——这一轮的结论涉及背调发现，不适合对外披露，
     * 只说明阶段与后续安排（版式与初筛通知共用 NoticeHtml，措辞按阶段区分）。
     */
    public void writeInterviewRejections(Map<String, Object> jd, List<String> names) {
        if (names == null || names.isEmpty()) {
            return;
        }
        String title = jd == null || jd.get("title") == null ? "该岗位" : String.valueOf(jd.get("title"));
        for (String name : names) {
            try {
                AgentTools.writeDoc("html",
                        "面试未通过通知-" + NoticeHtml.safeName(name) + ".html",
                        NoticeHtml.render(NoticeHtml.Stage.INTERVIEW, title, name, java.util.List.of()));
            } catch (Exception e) {
                throw new RuntimeException("面试未通过通知落盘失败：" + name, e);
            }
        }
    }

    /**
     * 把人工确认的「录用条件」拼成薪酬明细（纯计算，可单测）。
     *
     * <p>口径：月薪 + 每月补贴 为月度现金；年包估算 = 月度现金 × (12 + 年终奖月数) + 签字费。
     * 绩效占比、试用期长度这些只作为描述性字段，不参与计算——口径越少越不容易和用户对不上。
     * 认不出的数字一律当 0，绝不让脏输入把整份 offer 弄崩。
     */
    static Map<String, Object> assembleCompensation(Map<String, Object> terms) {
        Map<String, Object> c = new LinkedHashMap<>();
        if (terms == null || terms.isEmpty()) return c;
        int base = moneyOf(terms.get("baseSalary"), 0);
        int allowance = moneyOf(terms.get("allowanceAmount"), 0);
        int bonusMonths = moneyOf(terms.get("annualBonusMonths"), 0);
        int signOn = moneyOf(terms.get("signOnBonus"), 0);
        int probationMonths = moneyOf(terms.get("probationMonths"), 0);
        int probationRatio = moneyOf(terms.get("probationRatio"), 100);
        int monthlyCash = base + allowance;
        int annualTotal = monthlyCash * (12 + bonusMonths) + signOn;

        c.put("baseSalary", base);
        c.put("allowanceAmount", allowance);
        c.put("annualBonusMonths", bonusMonths);
        c.put("signOnBonus", signOn);
        c.put("monthlyCash", monthlyCash);
        c.put("annualTotal", annualTotal);
        c.put("probationMonths", probationMonths);
        c.put("probationRatio", probationRatio);
        c.put("probationSalary", probationMonths > 0 ? Math.round(base * probationRatio / 100f) : 0);
        c.put("position", str(terms.get("position")));
        c.put("jobLevel", str(terms.get("level")));
        c.put("reportsTo", str(terms.get("reportsTo")));
        c.put("location", str(terms.get("location")));
        c.put("employmentType", str(terms.get("employmentType")));
        c.put("startDate", str(terms.get("startDate")));
        c.put("offerValidUntil", str(terms.get("offerValidUntil")));
        c.put("expectedSalary", str(terms.get("expectedSalary")));
        c.put("perfRatio", str(terms.get("perfRatio")));
        c.put("allowance", str(terms.get("allowance")));
        c.put("conditions", terms.get("conditions") instanceof List<?> l ? l : List.of());
        return c;
    }

    /**
     * offer 汇总表（全组一张 CSV，每人一行）：每次生成 offer 后重写，随时反映最新状态。
     * 与笔试/面试成绩汇总表同一套做法（带 BOM，Excel 直接打开不乱码）。
     */
    public void writeOfferCsv(List<Map<String, Object>> rows) {
        try {
            StringBuilder sb = new StringBuilder("\uFEFF");
            sb.append("候选人编号,姓名,岗位,职级,基本月薪,每月补贴,月度现金,年终奖(月),签字费,预估年包,试用期(月),试用期薪资比例,到岗日期,offer有效期,确认状态\n");
            for (Map<String, Object> row : rows) {
                Map<String, Object> c = assembleCompensation(asMap(row.get("terms")));
                if (c.isEmpty()) continue;
                sb.append(csv(row.get("id"))).append(',')
                        .append(csv(row.get("name"))).append(',')
                        .append(csv(c.get("position"))).append(',')
                        .append(csv(c.get("jobLevel"))).append(',')
                        .append(c.get("baseSalary")).append(',')
                        .append(c.get("allowanceAmount")).append(',')
                        .append(c.get("monthlyCash")).append(',')
                        .append(c.get("annualBonusMonths")).append(',')
                        .append(c.get("signOnBonus")).append(',')
                        .append(c.get("annualTotal")).append(',')
                        .append(c.get("probationMonths")).append(',')
                        .append(c.get("probationRatio")).append("%,")
                        .append(csv(c.get("startDate"))).append(',')
                        .append(csv(c.get("offerValidUntil"))).append(',')
                        .append(csv(decisionCn(row.get("decision"))))
                        .append('\n');
            }
            if (sb.length() <= 1) return; // 一个人都没填，不落一张只有表头的空表
            AgentTools.writeDoc("csv", "offer 汇总表.csv", sb.toString());
        } catch (Exception e) {
            throw new RuntimeException("ConciergeAgent 汇总落盘失败", e);
        }
    }

    private static String decisionCn(Object decision) {
        String d = String.valueOf(decision == null ? "" : decision);
        if (d.contains("accepted")) return "已接受";
        if (d.contains("rejected")) return "已拒绝";
        return "待确认";
    }

    private static String csv(Object v) {
        String s = String.valueOf(v == null ? "" : v);
        if (s.contains(",") || s.contains("\"") || s.contains("\n")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private static String str(Object o) {
        String s = String.valueOf(o == null ? "" : o).trim();
        return "null".equals(s) ? "" : s;
    }

    /**
     * 录用条件的字段名 → 中文。
     *
     * <p>为什么要有这张表：模型很爱在正文里引用输入里的字段名（「以录用条件为准：baseSalary=25000/月…」，
     * 用户反馈过）。喂中文键，它写出来的就是中文；再留一份反向替换，兜住它自己造英文键的情况。
     */
    private static final Map<String, String> TERM_CN = Map.ofEntries(
            Map.entry("position", "岗位名称"),
            Map.entry("jobLevel", "职级"),
            Map.entry("reportsTo", "汇报对象"),
            Map.entry("location", "工作地点"),
            Map.entry("employmentType", "用工形式"),
            Map.entry("startDate", "期望到岗日期"),
            Map.entry("offerValidUntil", "offer有效期"),
            Map.entry("expectedSalary", "候选人期望薪资"),
            Map.entry("baseSalary", "基本月薪"),
            // allowance 是补贴的文字说明（allowanceAmount 才是金额），前端表单里两个都有，
            // 漏了它模型就会原样写「allowance 300元/月」（用户反馈）
            Map.entry("allowance", "补贴说明"),
            Map.entry("allowanceAmount", "每月补贴"),
            Map.entry("perfRatio", "绩效占比"),
            Map.entry("annualBonusMonths", "年终奖月数"),
            Map.entry("signOnBonus", "签字费"),
            Map.entry("equity", "股权期权"),
            Map.entry("probationMonths", "试用期月数"),
            Map.entry("probationRatio", "试用期计薪比例"),
            Map.entry("conditions", "录用前置条件"));

    /**
     * 跨环节词汇：模型会照抄输入里的键名与取值。
     *
     * <p>用户反馈的原文：「…scorecard 92分、shortlist，测评风险low」——查下来根在**输入**：
     * 喂给模型的 JSON 里就写着 {@code scorecard={verdict=shortlist, score=92}}、
     * {@code assessment={fit_score=90, risk_level=low}}，它当然照抄。
     * 跟「录用条件」是一个道理（见第 16 轮的教训）：**根在输入，不在渲染**。
     * 所以两边都过一遍：输入侧先换成中文（模型没得抄），输出侧再兜一遍（它自己造的英文也换掉）。
     */
    private static final Map<String, String> STAGE_CN = Map.ofEntries(
            Map.entry("candidate", "候选人"),
            Map.entry("jd", "岗位需求"),
            Map.entry("resume", "简历"),
            Map.entry("minutes", "面试纪要"),
            Map.entry("scorecard", "简历评价"),
            Map.entry("assessment", "测评背调"),
            Map.entry("score", "得分"),
            Map.entry("verdict", "结论"),
            Map.entry("matched", "匹配项"),
            Map.entry("gaps", "差距项"),
            Map.entry("level", "等级"),
            Map.entry("risk_level", "风险等级"),
            Map.entry("fit_score", "匹配分"),
            // 取值（结论与风险等级）也要给中文，否则模型写「shortlist」「low」
            Map.entry("shortlist", "进入录用名单"),
            Map.entry("hold", "待定"),
            Map.entry("reject", "未通过"),
            Map.entry("pass", "通过"),
            Map.entry("fail", "未通过"),
            Map.entry("low", "低"),
            Map.entry("mid", "中"),
            Map.entry("medium", "中"),
            Map.entry("high", "高"));

    /** 键名中文化：录用条件的字段名 + 其它环节的字段名 */
    private static String cnKey(String key) {
        return TERM_CN.getOrDefault(key, STAGE_CN.getOrDefault(key, key));
    }

    /**
     * 给模型看的输入：键名与「英文取值」全部中文化（只动这一份副本，不动真实数据）。
     *
     * <p>取值只做**整串精确匹配**——只把 shortlist / low 这类枚举值换成中文，
     * 不做子串替换，免得把公司名、产品名里的英文也搅进去。
     */
    private static Map<String, Object> localizeInput(Map<String, Object> payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : payload.entrySet()) {
            out.put(cnKey(e.getKey()), localizeValue(e.getValue()));
        }
        return out;
    }

    private static Object localizeValue(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(cnKey(String.valueOf(e.getKey())), localizeValue(e.getValue()));
            }
            return out;
        }
        if (v instanceof List<?> list) {
            List<Object> out = new java.util.ArrayList<>(list.size());
            for (Object item : list) out.add(localizeValue(item));
            return out;
        }
        if (v instanceof String s) {
            String cn = STAGE_CN.get(s.trim());
            return cn == null ? v : cn;
        }
        return v;
    }

    /** 模型文案里残留的英文字段名 / 英文取值 → 中文（只替换整词，别把 normal 里的「no」也换了） */
    static String localizeText(String text) {
        String out = String.valueOf(text == null ? "" : text);
        if (out.isEmpty()) return out;
        for (Map.Entry<String, String> e : TERM_CN.entrySet()) {
            out = out.replaceAll("(?<![A-Za-z0-9_])" + e.getKey() + "(?![A-Za-z0-9_])", e.getValue());
        }
        for (Map.Entry<String, String> e : STAGE_CN.entrySet()) {
            out = out.replaceAll("(?<![A-Za-z0-9_])" + e.getKey() + "(?![A-Za-z0-9_])", e.getValue());
        }
        return out;
    }

    /**
     * 把结果里所有**文案**过一遍中文化（谈薪策略、条款、入职计划、思考过程都会展示给用户）。
     *
     * <p>就地替换而不是重建对象：dispatcher 与调用方都持有 `result` 里嵌套 map 的引用，
     * 换新对象会让它们读到旧值（本地单测就是这么当场翻的车）。
     * 只换「值」，键名是前后端契约一个都不能动；数字 / 布尔原样保留。
     */
    @SuppressWarnings("unchecked")
    private static void localizeResultText(Map<String, Object> result) {
        for (Map.Entry<String, Object> e : result.entrySet()) {
            Object v = e.getValue();
            if (v instanceof String s) {
                e.setValue(localizeText(s));
            } else if (v instanceof List<?> list) {
                e.setValue(localizeList(list));
            } else if (v instanceof Map<?, ?> m) {
                localizeResultText((Map<String, Object>) m);
            }
        }
    }

    private static List<Object> localizeList(List<?> list) {
        List<Object> out = new java.util.ArrayList<>();
        for (Object item : list) {
            if (item instanceof String s) out.add(localizeText(s));
            else if (item instanceof List<?> inner) out.add(localizeList(inner));
            else if (item instanceof Map<?, ?> m) {
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (e.getValue() instanceof String s) ((Map<Object, Object>) m).put(e.getKey(), localizeText(s));
                }
                out.add(m);
            } else {
                out.add(item);
            }
        }
        return out;
    }

    /** 任意值 → 字符串列表（模型可能给数组、只给一个字符串，或什么都不给） */
    private static List<String> strList(Object o) {
        List<String> out = new java.util.ArrayList<>();
        if (o instanceof List<?> list) {
            for (Object item : list) {
                String s = str(item);
                if (!s.isEmpty()) out.add(s);
            }
        } else {
            String s = str(o);
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    /** 金额加千分位，长数字一眼能读 */
    private static String money(Object v) {
        return String.format("%,d", moneyOf(v, 0));
    }

    /** 空值统一显示「—」，表格里不留空白格 */
    private static String empty(Object v) {
        String s = str(v);
        return s.isEmpty() ? "—" : s;
    }

    private static String row2(String k, String v) {
        return "<tr><th>" + escapeHtml(k) + "</th><td>" + escapeHtml(v) + "</td></tr>";
    }

    private static int moneyOf(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        // 「18K」「18000 元」「1.8万」都尽量认出来，认不出用兜底值
        String s = String.valueOf(o == null ? "" : o).replaceAll("[^0-9.万亿kK]", "").trim();
        if (s.isEmpty()) return def;
        try {
            double v = Double.parseDouble(s.replace("万", "").replace("k", "").replace("K", ""));
            if (String.valueOf(o).contains("万")) v *= 10000;
            if (String.valueOf(o).toLowerCase().contains("k") && !String.valueOf(o).contains("万")) v *= 1000;
            return (int) Math.round(v);
        } catch (RuntimeException e) {
            return def;
        }
    }

    /**
     * 谈薪策略与入职计划的新字段：模型漏了就补空结构。
     * 补空之后页面与报告只是少几段内容，不会因为某个可选字段缺失就整份 offer 失败。
     */
    @SuppressWarnings("unchecked")
    private static void fillDefaults(Map<String, Object> result) {
        Object nego = result.get("negotiation");
        if (!(nego instanceof Map)) {
            Map<String, Object> empty = new LinkedHashMap<>();
            empty.put("acceptanceRisk", "需人工判断");
            empty.put("rationale", "模型未给出谈薪依据，请人工确认。");
            result.put("negotiation", empty);
        } else {
            Map<String, Object> n = (Map<String, Object>) nego;
            for (String key : List.of("target", "floor", "ceil", "acceptanceRisk", "rationale", "walkAwayPlan")) {
                n.putIfAbsent(key, "");
            }
            if (!(n.get("alternatives") instanceof List)) n.put("alternatives", List.of());
        }
        Object planObj = result.get("plan");
        if (planObj instanceof Map) {
            Map<String, Object> plan = (Map<String, Object>) planObj;
            for (String key : List.of("d30", "d60", "d90", "training", "tools", "checkpoints")) {
                if (!(plan.get(key) instanceof List)) plan.put(key, List.of());
            }
            plan.putIfAbsent("buddy", "");
        }
    }

    @SuppressWarnings("unchecked")
    private String buildOfferHtml(Map<String, Object> result, Map<String, Object> payload) {
        Map<String, Object> offer = (Map<String, Object>) result.get("offer");
        Map<String, Object> plan = (Map<String, Object>) result.get("plan");
        Map<String, Object> candidate = payload == null ? null : (Map<String, Object>) payload.get("candidate");
        String position = String.valueOf(offer.get("position"));
        String candidateName = candidate == null ? "候选人" : String.valueOf(candidate.getOrDefault("name", "候选人"));
        String time = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

        List<String> clauses = offer.get("clauses") instanceof List ? (List<String>) offer.get("clauses") : List.of();
        List<String> week = plan.get("week1_4") instanceof List ? (List<String>) plan.get("week1_4") : List.of();
        List<String> goals = plan.get("goals") instanceof List ? (List<String>) plan.get("goals") : List.of();

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html><html lang=\"zh-CN\"><head><meta charset=\"UTF-8\">");
        sb.append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1.0\">");
        sb.append("<title>Offer · ").append(escapeHtml(position)).append("</title>");
        sb.append("<style>").append(STYLE).append("</style></head><body><div class=\"page\">");

        sb.append("<header class=\"hero\"><div class=\"crumb\">录用意向书 · OFFER LETTER</div>");
        sb.append("<h1>").append(escapeHtml(position)).append("</h1>");
        sb.append("<div class=\"sub\">致：").append(escapeHtml(candidateName)).append("　·　AI 招聘数字员工团队（HR Agent Team）</div></header>");

        sb.append("<div class=\"body\">");

        sb.append("<div class=\"grid\">");
        sb.append(kpi("候选人", candidateName, false));
        sb.append(kpi("岗位", position, true));
        sb.append(kpi("生成时间", time, true));
        sb.append("</div>");

        sb.append(sec("薪酬", "薪资与建议"));
        sb.append("<div class=\"card\"><div class=\"salary\">").append(escapeHtml(String.valueOf(offer.get("salary_range")))).append("</div>");
        sb.append("<div style=\"font-size:13.5px;color:#4a5d66;margin-top:8px\">").append(escapeHtml(String.valueOf(offer.get("suggested")))).append("</div></div>");

        // 人工确认过的录用条件：薪酬明细 + 岗位信息 + 录用前置条件（数字以这里为准）
        Map<String, Object> comp = offer.get("compensation") instanceof Map
                ? (Map<String, Object>) offer.get("compensation") : Map.of();
        if (!comp.isEmpty()) {
            sb.append(sec("明细", "薪酬明细（人工确认）"));
            sb.append("<table class=\"tb\"><tbody>");
            sb.append(row2("基本月薪", money(comp.get("baseSalary")) + " 元"));
            if (moneyOf(comp.get("allowanceAmount"), 0) > 0) {
                sb.append(row2("每月补贴", money(comp.get("allowanceAmount")) + " 元"));
            }
            sb.append(row2("月度现金", money(comp.get("monthlyCash")) + " 元"));
            sb.append(row2("年终奖", moneyOf(comp.get("annualBonusMonths"), 0) + " 个月"));
            if (moneyOf(comp.get("signOnBonus"), 0) > 0) {
                sb.append(row2("签字费", money(comp.get("signOnBonus")) + " 元"));
            }
            sb.append(row2("预估年包", money(comp.get("annualTotal")) + " 元"));
            if (moneyOf(comp.get("probationMonths"), 0) > 0) {
                sb.append(row2("试用期", moneyOf(comp.get("probationMonths"), 0) + " 个月 · 按 "
                        + moneyOf(comp.get("probationRatio"), 100) + "% 计薪（" + money(comp.get("probationSalary")) + " 元/月）"));
            } else {
                sb.append(row2("试用期", "无"));
            }
            sb.append("</tbody></table>");

            sb.append(sec("岗位", "岗位与入职信息"));
            sb.append("<table class=\"tb\"><tbody>");
            sb.append(row2("岗位", empty(comp.get("position"))));
            sb.append(row2("职级", empty(comp.get("jobLevel"))));
            sb.append(row2("汇报对象", empty(comp.get("reportsTo"))));
            sb.append(row2("工作地点", empty(comp.get("location"))));
            sb.append(row2("用工形式", empty(comp.get("employmentType"))));
            sb.append(row2("期望到岗", empty(comp.get("startDate"))));
            sb.append(row2("offer 有效期至", empty(comp.get("offerValidUntil"))));
            sb.append(row2("候选人期望薪资", empty(comp.get("expectedSalary"))));
            sb.append("</tbody></table>");

            if (comp.get("conditions") instanceof List<?> conds && !conds.isEmpty()) {
                sb.append(sec("条件", "录用前置条件"));
                sb.append("<div class=\"card\">").append(plainList(
                        conds.stream().map(String::valueOf).toList())).append("</div>");
            }
        }

        sb.append(sec("条款", "录用条款"));
        sb.append("<div class=\"card\">").append(plainList(clauses)).append("</div>");

        // 谈薪策略：内部材料，明确标注不对外发
        Map<String, Object> nego = result.get("negotiation") instanceof Map
                ? (Map<String, Object>) result.get("negotiation") : Map.of();
        if (!nego.isEmpty()) {
            sb.append(sec("谈薪", "谈薪策略（内部材料 · 不对外发送）"));
            sb.append("<table class=\"tb\"><tbody>");
            sb.append(row2("目标出价", empty(nego.get("target"))));
            sb.append(row2("谈判底线", empty(nego.get("floor"))));
            sb.append(row2("可接受上限", empty(nego.get("ceil"))));
            sb.append(row2("接受概率", empty(nego.get("acceptanceRisk"))));
            sb.append("</tbody></table>");
            sb.append("<div class=\"card\"><p class=\"h\">区间依据</p><p>")
              .append(escapeHtml(empty(nego.get("rationale")))).append("</p></div>");
            if (nego.get("alternatives") instanceof List<?> alts && !alts.isEmpty()) {
                sb.append("<div class=\"card\" style=\"margin-top:10px\"><p class=\"h\">给不了钱能给什么</p>")
                  .append(plainList(alts.stream().map(String::valueOf).toList())).append("</div>");
            }
            if (!empty(nego.get("walkAwayPlan")).equals("—")) {
                sb.append("<div class=\"card\" style=\"margin-top:10px\"><p class=\"h\">谈崩预案</p><p>")
                  .append(escapeHtml(empty(nego.get("walkAwayPlan")))).append("</p></div>");
            }
        }

        sb.append(sec("计划", "入职计划"));
        sb.append("<div class=\"card\"><p class=\"h\">第 1–4 周安排</p>").append(plainList(week)).append("</div>");
        sb.append("<div class=\"card\" style=\"margin-top:10px\"><p class=\"h\">入职目标</p>").append(plainList(goals)).append("</div>");

        // 30 / 60 / 90 天里程碑（三段并排，节点头一眼能看出节奏）
        String d30 = plainList(strList(plan.get("d30")));
        String d60 = plainList(strList(plan.get("d60")));
        String d90 = plainList(strList(plan.get("d90")));
        if (!d30.isBlank() || !d60.isBlank() || !d90.isBlank()) {
            sb.append("<div class=\"grid\" style=\"grid-template-columns:repeat(3,1fr)\">");
            sb.append("<div class=\"card\"><p class=\"h\">30 天</p>").append(d30).append("</div>");
            sb.append("<div class=\"card\"><p class=\"h\">60 天</p>").append(d60).append("</div>");
            sb.append("<div class=\"card\"><p class=\"h\">90 天</p>").append(d90).append("</div>");
            sb.append("</div>");
        }

        String buddy = empty(plan.get("buddy"));
        String training = plainList(strList(plan.get("training")));
        String tools = plainList(strList(plan.get("tools")));
        String checkpoints = plainList(strList(plan.get("checkpoints")));
        if (!"—".equals(buddy) || !training.isBlank() || !tools.isBlank() || !checkpoints.isBlank()) {
            sb.append("<table class=\"tb\" style=\"margin-top:10px\"><tbody>");
            if (!"—".equals(buddy)) sb.append(row2("导师 / 伙伴", buddy));
            if (!tools.isBlank()) sb.append("<tr><th>首周账号与工具</th><td>").append(tools).append("</td></tr>");
            if (!training.isBlank()) sb.append("<tr><th>首月培训</th><td>").append(training).append("</td></tr>");
            if (!checkpoints.isBlank()) sb.append("<tr><th>检查点</th><td>").append(checkpoints).append("</td></tr>");
            sb.append("</tbody></table>");
        }

        // 分析说明已由前端「思考过程」展示，报告里不再重复
        sb.append("<div class=\"note\">本 Offer 为草案，需经人工确认后生效（offer 关卡）。筛选标准回写：")
          .append(escapeHtml(String.valueOf(plan.get("screening_feedback")))).append("</div>");

        sb.append("<div class=\"sign\"><div class=\"company\">AI 招聘数字员工团队（HR Agent Team）</div><div class=\"date\">").append(time).append("</div></div>");
        sb.append("</div>");

        sb.append("<footer>offer.onboard · offer 与入职管家自动生成</footer>");
        sb.append(sealImageTag());
        sb.append("</div></body></html>");
        return sb.toString();
    }

    private void validate(Map<String, Object> result) {
        if (result == null || !(result.get("offer") instanceof Map<?, ?> offer)
                || !(result.get("plan") instanceof Map<?, ?> plan)
                || offer.get("position") == null || offer.get("salary_range") == null
                || offer.get("suggested") == null || !(offer.get("clauses") instanceof java.util.List<?>)
                || !(plan.get("week1_4") instanceof java.util.List<?>)
                || !(plan.get("goals") instanceof java.util.List<?>)
                || plan.get("screening_feedback") == null
                || String.valueOf(result.getOrDefault("reason", "")).isBlank()) {
            throw new IllegalArgumentException("Offer/入职计划结果字段不完整");
        }
    }

    private static String sec(String label, String title) {
        return "<p class=\"eyebrow\">" + escapeHtml(label) + "</p><h2>" + escapeHtml(title) + "</h2>";
    }

    private static String kpi(String k, String v, boolean blue) {
        return "<div class=\"kpi" + (blue ? " blue" : "") + "\"><div class=\"k\">" + escapeHtml(k) + "</div><div class=\"v\">" + escapeHtml(v) + "</div></div>";
    }

    private static String plainList(List<String> items) {
        if (items == null || items.isEmpty()) return "<p style=\"font-size:12.5px;color:var(--mut)\">— 无 —</p>";
        StringBuilder sb = new StringBuilder("<ul class=\"plain\">");
        for (String item : items) {
            sb.append("<li>").append(escapeHtml(item)).append("</li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    private static String sealImageTag() {
        String src = sealDataUri();
        if (src.isEmpty()) return "";
        return "<img class=\"seal\" src=\"" + src + "\" alt=\"印章\">";
    }

    /**
     * 印章图片 → data URI（直接内嵌进 HTML，报告单独发出去也能看到章）。
     *
     * <p>查找顺序：
     * <ol>
     *   <li>{@code HR_SEAL_PATH} 指定的文件（想换章不改代码）；</li>
     *   <li>**classpath 里的 {@code img/seal_custom.png}**（打包进 jar，容器里 cwd 是什么都能找到）——主要方式；</li>
     *   <li>开发时项目里的相对路径（IDEA 直接跑 main 的场景）。</li>
     * </ol>
     *
     * <p>以前只有第 3 种：依赖"当前工作目录恰好是项目目录"，所以**在 Docker 容器里（cwd=/app）
     * 印章一直加载不到**，报告里是空的。现在图片随包走，到哪都在。
     */
    private static String sealDataUri() {
        String env = System.getenv("HR_SEAL_PATH");
        if (env != null && !env.isBlank()) {
            String s = readSealFile(env);
            if (s != null) return s;
        }
        String fromJar = readSealResource("img/seal_custom.png");
        if (fromJar != null) return fromJar;
        for (String rel : new String[]{"img/seal_custom.png", "hr-agent-team/img/seal_custom.png"}) {
            String s = readSealFile(rel);
            if (s != null) return s;
        }
        return "";
    }

    /** 从 classpath（打成 jar 后也能读到）读取印章 */
    private static String readSealResource(String name) {
        try (java.io.InputStream in = ConciergeAgent.class.getClassLoader().getResourceAsStream(name)) {
            if (in == null) return null;
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(in.readAllBytes());
        } catch (Exception e) {
            return null;
        }
    }

    /** 从磁盘相对路径读取印章（开发环境兜底） */
    private static String readSealFile(String path) {
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(path));
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private static String escapeHtml(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    // 自测：右键运行 main
    public static void main(String[] args) {
        ConciergeAgent agent = new ConciergeAgent();
        Map<String, Object> sample = new LinkedHashMap<>();
        sample.put("candidate", Map.of("candidate_id", "C-1", "name", "张三"));
        sample.put("assessment", Map.of("level", "proficient", "score", 80, "risk_level", "low", "fit_score", 80));
        sample.put("salary_data", "数据分析师一线城市约 15-25K");
        System.out.println(agent.run(sample));
    }
}

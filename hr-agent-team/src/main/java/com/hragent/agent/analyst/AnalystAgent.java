package com.hragent.agent.analyst;

import com.hragent.agent.Agent;
import com.hragent.agent.assessor.AssessorAgent;
import com.hragent.agent.concierge.ConciergeAgent;
import com.hragent.agent.interviewer.InterviewerAgent;
import com.hragent.agent.scout.ScoutAgent;
import com.hragent.common.DeepSeekClient;
import com.hragent.common.Scores;
import com.hragent.common.Texts;
import com.hragent.tool.AgentTools;
import static com.hragent.common.Jsons.esc;
import static com.hragent.common.Jsons.asMap;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 招聘分析师（成员 A 专属）· 技能 talent.analyze · 文件 agent/analyst/AnalystAgent.java
 *
 * 职责：把模糊招聘诉求 → 需求澄清(demand) + 结构化 JD(jd) + 候选人能力画像(persona)。
 *       无上游，产出交给下游 B(简历猎手)。
 *
 * run() 输出字段（严格照 SKILLS技能注册表 + all_skill 2.2 数据契约，不自创）：
 *   demand  { clarified, missing[], questions[] }                         // DemandClarified
 *   jd      { jd_id, title, responsibilities[], hard_requirements[], nice_to_have[] }  // JD
 *   persona { core_competencies[], soft_traits[] }                          // Persona
 *   reason
 *
 * 并且 run() 内真调用工具 doc_writer 落成 3 份可验收成果文件：
 *   需求澄清说明.md（报告）、结构化岗位描述.html（网页，含候选人能力画像）。
 */
public class AnalystAgent implements Agent {

    private static final String ROLE =
            "你是「招聘分析师」，招聘流程开头的数字员工，技能 id：talent.analyze。\n" +
            "职责：把业务方一句模糊的招聘诉求，澄清为结构化需求，并生成结构化 JD（岗位描述）与候选人能力画像。无上游，产出交给下游简历猎手（B）。\n" +
            "\n" +
            "输入字段：\n" +
            "- raw_demand：业务方原始招聘诉求（必填）\n" +
            "- context：可选，团队/项目背景\n" +
            "\n" +
            "你必须只输出一个合法 JSON 对象，不要输出任何解释文字、不要用 markdown 代码块包裹。字段与结构严格如下：\n" +
            "{\n" +
            "  demand: {\n" +
            "    clarified: 澄清后的完整需求陈述（字符串）,\n" +
            "    missing: 仍待确认的信息点（字符串数组）,\n" +
            "    questions: 生成给业务方的追问清单（字符串数组）\n" +
            "  },\n" +
            "  jd: {\n" +
            "    jd_id: 岗位唯一标识（字符串）,\n" +
            "    title: 岗位名称（字符串）,\n" +
            "    responsibilities: 岗位职责（字符串数组）,\n" +
            "    hard_requirements: 硬性要求，如学历/年限/必备技能（字符串数组）,\n" +
            "    nice_to_have: 加分项（字符串数组）\n" +
            "  },\n" +
            "  persona: {\n" +
            "    core_competencies: 核心能力标准表（对象数组，每项 {name, required}）：\n" +
            "        name 是能力名（如「SQL 与数据建模」）；\n" +
            "        required 是**该能力对本岗位的要求强度**，取 0-100 的整数（注意：这是岗位要求，不是候选人得分）；\n" +
            "        要求：共 4-6 条；每条数值必须**互不相同**，相邻条目至少相差 5 分；\n" +
            "        不要都给整十（禁止 80/80/80 这种整齐值）；\n" +
            "        最高不要超过 95（要给候选人留出可比较、可超越的空间，不要满格），最低不低于 20；\n" +
            "        数值越高表示这条能力对本岗位越关键。\n" +
            "        示例：required 88 / 76 / 64 / 41 分别表示「要求极高 / 高 / 中等 / 偏低」。\n" +
            "        下游「测评背调员」会拿这张标准表逐条对比候选人的实际水平，所以数值要有区分度。\n" +
            "    soft_traits: 软素质/性格特质（字符串数组）\n" +
            "  },\n" +
            "  reason: 完整思考过程（字符串，要详细展开，建议 300 字以上）：先说明澄清思路与关键假设，再逐步推演 JD 各字段与能力画像的取舍依据、备选方案，最后结论/风险/下一步。\n" +
            "}\n" +
            "\n" +
            "硬性约束：\n" +
            "1. 字段名、层级、类型必须与上面完全一致，禁止自创字段、禁止缺字段、禁止返回 null；\n" +
            "2. 除 persona.core_competencies 是对象数组（元素为 {name, required}）外，其余数组字段必须是字符串数组；\n" +
            "3. reason 要详尽展开思考过程（不要只写四段式简句）：先讲目标与输入，再逐步推演每个判断的依据、权衡与不确定点，最后结论/风险/下一步；\n" +
            "4. 禁止编造：信息不足时，把不确定项写进 demand.missing 与 demand.questions，绝不凭空补充；\n" +
            "5. 术语统一：用「候选人」（不用求职者/应聘者）、「岗位描述 JD」（不用招聘广告）、「能力画像」（不用人才画像）；\n" +
            "6. 本 agent 会用工具 doc_writer 把结果落成文件；落文件由代码在 run() 中调用，你只负责输出上面的 JSON。\n" +
            "\n" +
            "追问必须逐轮收敛（重要，违反即为不合格输出）：\n" +
            "7. 输入里如果给了「已经确认的信息」，那些问题一律**不得再出现在 demand.questions 里**，\n" +
            "   也不许换个说法、拆成两问、或换个角度重新问一遍；\n" +
            "8. 信息已经足够写出完整 JD（职责、硬性要求、加分项、能力画像都能落地）时，\n" +
            "   demand.missing 与 demand.questions 必须返回**空数组**；不要为了显得严谨而硬凑问题。\n" +
            "   追问是可选补充，凑数提问会让业务方陷入无休止的问答，这是明确禁止的。";

    private final DeepSeekClient client = new DeepSeekClient();

    @Override
    public Map<String, Object> run(Map<String, Object> payload) {
        try {
            String userInput = buildUserInput(payload);

            // 1) 调模型，要求返回符合契约字段的 JSON
            Map<String, Object> result = client.callJson(ROLE, userInput);

            // 2) 校验并规整字段，不合格就抛错（宁可失败，不硬编造）
            Map<String, Object> validated = validateAndNormalize(result);

            // 3) 🔧 真调用工具 doc_writer：把成果落成 3 份可验收文件（报告/网页/表格）
            writeDeliverables(validated);

            return validated;
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("AnalystAgent 执行失败：" + e.getMessage(), e);
        }
    }

    /** 读取并校验输入，拼成给模型的 user 消息。 */
    private String buildUserInput(Map<String, Object> payload) {
        if (payload == null) {
            throw new IllegalArgumentException("输入 payload 为空");
        }
        String rawDemand = asString(payload.get("raw_demand")).trim();
        if (rawDemand.isEmpty()) {
            throw new IllegalArgumentException("缺少必填输入字段 raw_demand");
        }
        String context = asString(payload.get("context")).trim();

        StringBuilder sb = new StringBuilder();
        sb.append("原始招聘诉求(raw_demand)：").append(rawDemand).append("\n");
        if (!context.isEmpty()) {
            sb.append("补充上下文(context)：").append(context).append("\n");
        }

        // 已经答过的追问：这是「逐轮收敛」的关键——不告诉模型哪些问过了，它就会换个说法一直问
        List<?> answered = payload.get("answered") instanceof List<?> l ? l : List.of();
        List<String> qa = new ArrayList<>();
        for (Object o : answered) {
            if (o instanceof Map<?, ?> m && m.get("q") != null) {
                String q = String.valueOf(m.get("q")).trim();
                String a = String.valueOf(m.get("a") == null ? "" : m.get("a")).trim();
                if (!q.isEmpty() && !a.isEmpty()) {
                    qa.add("Q: " + q + "  →  A: " + a);
                }
            }
        }
        if (!qa.isEmpty()) {
            sb.append("\n已经确认的信息（业务方已经回答过下面这些问题，")
                    .append("生成 demand.questions 时必须把它们全部排除，也不许换个说法再问）：\n");
            for (String line : qa) {
                sb.append("  ").append(line).append("\n");
            }
        }

        sb.append("请严格按系统提示词 ROLE 的要求，只输出一个合法 JSON 对象。");
        return sb.toString();
    }

    /** 按契约校验/规整模型输出；核心字段缺失时抛错，绝不用占位数据糊弄。 */
    private Map<String, Object> validateAndNormalize(Map<String, Object> result) {
        if (result == null) {
            throw new IllegalStateException("模型未返回结果");
        }

        // demand（DemandClarified）
        Map<String, Object> demand = asMap(result.get("demand"));
        String clarified = asString(demand.get("clarified")).trim();
        if (clarified.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 demand.clarified");
        }
        Map<String, Object> demandOut = new LinkedHashMap<>();
        demandOut.put("clarified", clarified);
        demandOut.put("missing", asStringList(demand.get("missing")));
        demandOut.put("questions", asStringList(demand.get("questions")));

        // jd（JD）
        Map<String, Object> jd = asMap(result.get("jd"));
        String title = asString(jd.get("title")).trim();
        if (title.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 jd.title");
        }
        String jdId = asString(jd.get("jd_id")).trim();
        if (jdId.isEmpty()) {
            jdId = "JD-" + UUID.randomUUID().toString().substring(0, 8);
        }
        Map<String, Object> jdOut = new LinkedHashMap<>();
        jdOut.put("jd_id", jdId);
        jdOut.put("title", title);
        jdOut.put("responsibilities", asStringList(jd.get("responsibilities")));
        jdOut.put("hard_requirements", asStringList(jd.get("hard_requirements")));
        jdOut.put("nice_to_have", asStringList(jd.get("nice_to_have")));

        // persona（Persona）：能力画像 = 带重要度的核心能力 + 软素质
        // 说明：原来的「人物速写」「文化契合提示」已按需求移除——它们既不可核对也不好用，
        // 只会让画像段落变长；A 的产出只保留能逐条对比的东西（能力标准表 + 软素质）。
        Map<String, Object> persona = asMap(result.get("persona"));
        List<Map<String, Object>> core = asCompetencyList(persona.get("core_competencies"));
        if (core.isEmpty()) {
            throw new IllegalStateException("模型输出缺少字段 persona.core_competencies");
        }
        Map<String, Object> personaOut = new LinkedHashMap<>();
        personaOut.put("core_competencies", core);
        personaOut.put("soft_traits", asStringList(persona.get("soft_traits")));

        // 顶层只保留契约规定的 4 个字段
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("demand", demandOut);
        out.put("jd", jdOut);
        out.put("persona", personaOut);
        out.put("reason", asString(result.get("reason")).trim());
        return out;
    }

    /** 真调用 doc_writer，把两份成果分别落成文件。 */
    @SuppressWarnings("unchecked")
    private void writeDeliverables(Map<String, Object> result) throws Exception {
        Map<String, Object> demand = (Map<String, Object>) result.get("demand");

        // 需求澄清说明 → 报告(.md)
        AgentTools.writeDoc("md", "需求澄清说明.md", buildDemandReport(demand));
        // 结构化 JD → 网页(.html)：把整份 result 传入，可渲染更丰富的模块（画像/澄清/理由）
        // 候选人能力画像已并入这份网页的「05 · 候选人画像」段，不再单独出 CSV（一行数据不成表）
        AgentTools.writeDoc("html", "结构化岗位描述.html", buildJdHtml(result));
    }

    private String buildDemandReport(Map<String, Object> demand) {
        StringBuilder sb = new StringBuilder();
        sb.append("# 需求澄清说明\n\n");
        sb.append("## 澄清后需求陈述\n").append(demand.get("clarified")).append("\n\n");
        sb.append("## 待确认信息\n");
        for (String m : asStringList(demand.get("missing"))) {
            sb.append("- ").append(m).append("\n");
        }
        sb.append("\n## 追问清单\n");
        for (String q : asStringList(demand.get("questions"))) {
            sb.append("- ").append(q).append("\n");
        }
        return sb.toString();
    }

    /**
     * 生成「结构化 JD」网页：借鉴主流招聘网职位详情页的模块（岗位背景 → 编号职责/要求 →
     * 加分项 → 候选人画像 → 分析说明 + 职位信息/流水线/待澄清侧栏），浅绿+浅蓝、
     * 全内联 CSS、离线可开。只增强展示，不改 run() 返回的结构化 JSON。
     */
    @SuppressWarnings("unchecked")
    private String buildJdHtml(Map<String, Object> result) {
        Map<String, Object> jd = (Map<String, Object>) result.get("jd");
        Map<String, Object> demand = (Map<String, Object>) result.get("demand");
        Map<String, Object> persona = (Map<String, Object>) result.get("persona");

        String generatedAt = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));
        String title = asString(jd.get("title"));
        String jdId = asString(jd.get("jd_id"));
        String clarified = asString(demand.get("clarified"));
        String lead = clarified;
        String html = """
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
              <meta charset="UTF-8">
              <meta name="viewport" content="width=device-width, initial-scale=1.0">
              <title>@@TITLE@@ · 岗位描述(JD)</title>
              <style>
                :root{
                  --green:#3f9474; --green-deep:#2f7a5e; --green-soft:#e9f6f0;
                  --blue:#3f8dc2; --blue-deep:#2f6f9c; --blue-soft:#e9f3fb;
                  --ink:#2f4048; --muted:#74878f; --line:#e7efed; --card:#ffffff;
                  --r:14px;
                }
                *{box-sizing:border-box;margin:0;padding:0}
                body{
                  font-family:-apple-system,BlinkMacSystemFont,"Segoe UI","PingFang SC","Microsoft YaHei",sans-serif;
                  color:var(--ink);line-height:1.6;-webkit-font-smoothing:antialiased;
                  background:linear-gradient(165deg,#e5f6ee 0%,#e8f2fb 46%,#f0f7fc 100%);
                  background-attachment:fixed;min-height:100vh;padding:20px 12px;
                }
                .page{position:relative;max-width:1080px;margin:0 auto;background:var(--card);
                      border-radius:18px;box-shadow:0 10px 30px rgba(60,120,120,.13);overflow:hidden}
                .hero{position:relative;background:linear-gradient(135deg,#3f9474 0%,#63b896 30%,#4f9fd0 72%,#3f8dc2 100%);
                      color:#fff;padding:26px 44px 22px;overflow:hidden}
                .hero::after{content:"";position:absolute;right:-70px;top:-120px;width:300px;height:300px;border-radius:50%;
                      background:radial-gradient(circle,rgba(255,255,255,.2),rgba(255,255,255,0) 70%)}
                .hero .crumb{font-size:11.5px;letter-spacing:2px;opacity:.92;margin-bottom:10px}
                .hero h1{font-size:29px;font-weight:700;letter-spacing:.5px;line-height:1.3}
                .hero .meta{margin-top:12px;display:flex;flex-wrap:wrap;gap:6px}
                .hero .pill{font-size:12px;background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.3);
                      padding:3px 10px;border-radius:999px}
                .hero .lead{margin-top:12px;font-size:14px;max-width:700px;opacity:.96;line-height:1.7}
                .hero .lead:empty{display:none}
                .layout{display:grid;grid-template-columns:minmax(0,1fr) 286px}
                .col-main{padding:2px 34px 20px 44px}
                .col-side{display:flex;flex-direction:column;justify-content:space-between;gap:10px;
                      padding:16px 14px 16px 16px;background:linear-gradient(180deg,#f6fbf8,#f1f8fc);
                      border-left:1px solid var(--line)}
                .eyebrow{font-size:10.5px;letter-spacing:2px;color:var(--muted);margin:18px 0 0;font-weight:600}
                h2{display:flex;align-items:center;gap:9px;font-size:17px;font-weight:700;color:var(--ink)}
                h2::before{content:"";width:4px;height:16px;border-radius:2px;flex:0 0 auto;
                      background:linear-gradient(180deg,var(--green),var(--blue))}
                .hint{font-size:12.5px;color:var(--muted);margin:8px 0 0}
                .block{padding:12px 18px;border:1px solid var(--line);border-radius:12px;background:#fff;margin-top:6px}
                /* 不要用 max-width: NNch：ch 是数字 0 的宽度，汉字接近 2ch，
                   会把文字挤在左边、右边空一片。容器宽度就是行宽。 */
                .mission .mline{display:flex;align-items:baseline;gap:11px;position:relative;
                      padding-left:13px;border-left:3px solid transparent;
                      font-size:13.5px;line-height:1.95;color:#46705a}
                .mission .mline + .mline{margin-top:8px}
                /* 行首序号：细体浅绿数字，形成阅读节奏 */
                .mission .mno{flex:0 0 auto;font-style:normal;font-size:11px;font-weight:700;
                      letter-spacing:.04em;color:#a9c9b6}
                .mission .mtx{flex:1;min-width:0;word-break:break-word}
                .mission .mline.lead{border-left-color:var(--green);font-size:15.5px;font-weight:600;
                      color:#2b5540;line-height:1.9}
                .mission .mline.lead .mno{color:var(--green)}
                .num{list-style:none;margin:0;padding:0}
                /* 每条一张浅底卡：绿→奶白渐变，hover 轻轻浮起 */
                .num li{display:flex;gap:12px;align-items:flex-start;padding:11px 14px 11px 12px;
                      border:1px solid rgba(96,154,118,.2);border-radius:11px;
                      background:linear-gradient(135deg,rgba(236,246,240,.62),rgba(250,246,231,.5));
                      transition:transform .24s cubic-bezier(.33,.8,.5,1),box-shadow .24s ease,
                      border-color .24s ease,background .24s ease}
                .num li + li{margin-top:8px}
                .num li:hover{transform:translateY(-2px);border-color:rgba(76,158,120,.45);
                      background:linear-gradient(135deg,rgba(255,255,255,.92),rgba(252,250,242,.85));
                      box-shadow:0 6px 16px rgba(45,90,68,.12)}
                .num i{flex:0 0 auto;width:21px;height:21px;border-radius:50%;margin-top:1px;color:#fff;
                      font-style:normal;font-size:11.5px;font-weight:700;display:flex;align-items:center;
                      justify-content:center;background:linear-gradient(135deg,var(--green),#5eb37e);
                      box-shadow:0 2px 6px rgba(63,148,116,.3)}
                .num span{flex:1;min-width:0;font-size:13.5px;line-height:1.95;color:#38624c}
                .chips{display:flex;flex-wrap:wrap;gap:6px;margin-top:6px}
                .chip{display:inline-flex;align-items:center;padding:3px 10px;border-radius:999px;
                      font-size:12.5px;background:var(--green-soft);color:#2c7359;border:1px solid #cfe9dc}
                .chip.blue{background:var(--blue-soft);color:#2c6392;border-color:#d2e5f3}
                .chip i{font-style:normal;margin-left:6px;padding:1px 6px;border-radius:999px;
                      font-size:10.5px;font-weight:700;background:rgba(255,255,255,.75)}
                .chip.lv-hi{background:#dff0e6;border-color:#b6ddc7;color:#22664a}
                .chip.lv-hi i{color:#22664a}
                .chip.lv-mid{background:var(--green-soft);border-color:#cfe9dc;color:#2c7359}
                .chip.lv-mid i{color:#7d8f3e}
                .chip.lv-base{background:#fdf6e6;border-color:#eddfba;color:#8a7328}
                .chip.lv-base i{color:#9a8a3c}
                /* 核心能力标准表：一条能力一条能量条（0-100 的要求强度），后续测评会拿它跟候选人对比 */
                .std{margin-top:8px;border:1px solid var(--line);border-radius:12px;background:#fbfdfc;padding:12px 16px}
                .std-row{display:flex;align-items:center;gap:10px;flex-wrap:wrap;padding:6px 0}
                .std-name{flex:0 0 132px;font-size:13px;font-weight:600;color:#33525c}
                .std-track{flex:1 1 140px;min-width:110px;height:8px;border-radius:999px;background:rgba(63,148,116,.14);overflow:hidden}
                .std-fill{display:block;height:100%;border-radius:999px;background:linear-gradient(90deg,#3f9474,#5eb37e)}
                .std-fill.mid{background:linear-gradient(90deg,#7aa93f,#a8c15c)}
                .std-fill.base{background:linear-gradient(90deg,#cbbe74,#ded49c)}
                .std-val{flex:0 0 30px;text-align:right;font-size:13px;font-weight:700;color:#2c7359}
                .std-lv{flex:0 0 auto;font-size:11.5px;color:#5f7d6c}
                .std-hint{margin-top:6px;font-size:11.5px;color:#9aa8ad;line-height:1.7}
                .portrait{margin:0 0 4px;font-size:14px;font-weight:600;color:#2b5540;line-height:1.8;
                      padding-left:12px;border-left:3px solid var(--green)}
                .quote{margin-top:10px;padding:10px 14px 10px 40px;position:relative;border-radius:10px;
                      background:var(--green-soft);color:#33654f;font-size:13.5px;line-height:1.75}
                .quote::before{content:"“";position:absolute;left:13px;top:-8px;font-size:38px;
                      color:var(--green);font-family:Georgia,serif}
                .reason{white-space:pre-line;font-size:12.5px;color:#5c6f78;line-height:1.8;
                      background:#f6faf9;border:1px dashed #d5e6df;border-radius:10px;padding:10px 14px;margin-top:6px}
                .empty{color:var(--muted);font-size:12.5px}
                .card{border:1px solid var(--line);border-radius:12px;background:#fff;padding:12px 14px;
                      box-shadow:0 2px 8px rgba(60,120,120,.05)}
                .card h3{font-size:13px;color:var(--ink);display:flex;align-items:center;gap:7px;margin-bottom:8px}
                .card h3::before{content:"";width:7px;height:7px;border-radius:50%;flex:0 0 auto;
                      background:linear-gradient(135deg,var(--green),var(--blue))}
                .kv{display:flex;justify-content:space-between;gap:10px;font-size:12.5px;padding:3px 0;border-bottom:1px dashed #eef3f1}
                .kv:last-child{border-bottom:none}
                .kv dt{color:var(--muted);flex:0 0 auto}
                .kv dd{color:#33484f;font-weight:500;text-align:right}
                .pipe{list-style:none}
                .pipe li{position:relative;padding:2px 0 2px 20px;font-size:12.5px;color:#57707a}
                .pipe li::before{content:"";position:absolute;left:1px;top:8px;width:8px;height:8px;border-radius:50%;
                      background:#d7e3df}
                .pipe li.done{color:#2c7359;font-weight:500}
                .pipe li.done::before{background:var(--green);box-shadow:0 0 0 2px var(--green-soft)}
                .pipe li.now::after{content:"← 你在这里";margin-left:6px;font-size:11.5px;color:var(--blue)}
                .mini-h{font-size:11.5px;color:var(--muted);margin:6px 0 2px;font-weight:600}
                ul.plain{list-style:none}
                ul.plain li{position:relative;padding:3px 0 3px 15px;font-size:12.5px;color:#465a63}
                ul.plain li::before{content:"";position:absolute;left:0;top:10px;width:6px;height:6px;border-radius:2px;
                      background:linear-gradient(135deg,var(--green),var(--blue))}
                .ok{font-size:12.5px;color:#2c7359}
                footer{padding:10px 44px 20px;text-align:center;color:#94a6ad;font-size:11.5px;letter-spacing:.5px}
                @media (max-width:860px){ .layout{grid-template-columns:1fr}
                      .col-side{border-left:none;border-top:1px solid var(--line);justify-content:flex-start}
                      .hero{padding:22px 20px 18px} .col-main{padding:2px 16px 16px}
                      footer{padding:12px 16px 20px} }
              </style>
            </head>
            <body>
              <div class="page">
                <header class="hero">
                  <div class="crumb">岗位描述（JD）· 招聘分析师 talent.analyze</div>
                  <h1>@@TITLE@@</h1>
                  <div class="meta">
                    <span class="pill">岗位 ID · @@JDID@@</span>
                    <span class="pill">技能 talent.analyze</span>
                    <span class="pill">生成于 @@TIME@@</span>
                  </div>
                  <p class="lead">@@LEAD@@</p>
                </header>
                <div class="layout">
                  <div class="col-main">
                    <section>
                      <p class="eyebrow">01 · 岗位背景</p>
                      <h2>我们为什么招这个岗位</h2>
                      <div class="block mission">@@MISSION@@</div>
                    </section>
                    <section>
                      <p class="eyebrow">02 · 岗位职责</p>
                      <h2>你将负责</h2>
                      <div class="block">@@RESP@@</div>
                    </section>
                    <section>
                      <p class="eyebrow">03 · 任职要求</p>
                      <h2>我们希望候选人具备</h2>
                      <div class="block">@@HARD@@</div>
                    </section>
                    <section>
                      <p class="eyebrow">04 · 加分项</p>
                      <h2>如果你还拥有</h2>
                      <div class="block">@@NICE@@</div>
                    </section>
                    <section>
                      <p class="eyebrow">05 · 候选人画像</p>
                      <h2>我们希望你是这样的人</h2>
                      <div class="block">
                        <p class="hint">核心能力标准表（数值＝该能力对岗位的要求强度）</p>@@CORECHIPS@@
                        <p class="hint" style="margin-top:10px">软素质</p>@@SOFTCHIPS@@
                      </div>
                    </section>
                  </div>
                  <aside class="col-side">
                    <div class="card">
                      <h3>职位信息</h3>
                      <dl class="kv"><dt>职位名称</dt><dd>@@TITLE@@</dd></dl>
                      <dl class="kv"><dt>岗位 ID</dt><dd>@@JDID@@</dd></dl>
                      <dl class="kv"><dt>技能</dt><dd>talent.analyze</dd></dl>
                      <dl class="kv"><dt>生成时间</dt><dd>@@TIME@@</dd></dl>
                    </div>
                    <div class="card">
                      <h3>招聘流水线</h3>
                      <ul class="pipe">
                        <li class="done">需求澄清 · 本岗位</li>
                        <li class="done">结构化 JD · 本岗位</li>
                        <li class="now">简历猎手初筛（下一步）</li>
                        <li>面试官 · 题纲 / 纪要</li>
                        <li>测评背调 · 文化匹配</li>
                        <li>offer 与入职计划</li>
                      </ul>
                    </div>
                    <div class="card">
                      <h3>交简历猎手前 · 仍需澄清</h3>
                      @@OPEN@@
                    </div>
                  </aside>
                </div>
                <footer>结构化 JD · 由 招聘分析师 talent.analyze 自动生成　·　生成时间 @@TIME@@</footer>
              </div>
            </body>
            </html>
            """;
        return html
                .replace("@@JDID@@", esc(jdId))
                .replace("@@TITLE@@", esc(title))
                .replace("@@TIME@@", esc(generatedAt))
                .replace("@@LEAD@@", esc(lead))
                .replace("@@MISSION@@", missionHtml(clarified))
                .replace("@@RESP@@", numListHtml(asStringList(jd.get("responsibilities"))))
                .replace("@@HARD@@", numListHtml(asStringList(jd.get("hard_requirements"))))
                .replace("@@NICE@@", chipsHtml(asStringList(jd.get("nice_to_have")), true))
                .replace("@@CORECHIPS@@", levelChipsHtml(asCompetencyList(persona.get("core_competencies")))
                        + "<p class=\"std-hint\">后续「测评背调员」会拿这张标准表逐条对比候选人的实际水平，"
                        + "标出哪些满足、哪些不及格（见该候选人的《测评背调报告》）。</p>")
                .replace("@@SOFTCHIPS@@", chipsHtml(asStringList(persona.get("soft_traits")), true))
                .replace("@@OPEN@@", openItemsHtml(
                        asStringList(demand.get("missing")), asStringList(demand.get("questions"))))
                .replace("@@REASON@@", esc(asString(result.get("reason"))));
    }

    // ---------- 工具型辅助方法 ----------

    @SuppressWarnings("unchecked")

    private static String asString(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List) {
            for (Object item : (List<Object>) o) {
                if (item != null) {
                    out.add(String.valueOf(item));
                }
            }
        } else if (o != null) {
            out.add(String.valueOf(o));
        }
        return out;
    }

    /**
     * 核心能力统一成 [{name, required, level}]：
     * 模型偶尔会退化成字符串数组（["数据建模"]）或换用别的键名（level/weight），这里全部兜住，
     * 保证前端与报表拿到的都是同一形状。
     *
     * <p>required 是 0-100 的要求强度（标准表的“能量”），level 是代码按七档派生的中文标签。
     * 两者分开是为了：标签可读，条长可用真实数值——只给「高/中/基本」三档会导致所有条只有三种长度。
     */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> asCompetencyList(Object o) {
        List<Map<String, Object>> out = new ArrayList<>();
        List<Object> items = new ArrayList<>();
        if (o instanceof List) {
            items.addAll((List<Object>) o);
        } else if (o != null) {
            items.add(o);
        }
        for (Object item : items) {
            if (item == null) continue;
            String name;
            Integer required = null;
            Object rawLevel = null;
            if (item instanceof Map) {
                Map<String, Object> m = (Map<String, Object>) item;
                Object n = m.get("name");
                if (n == null) n = m.get("skill");
                if (n == null) n = m.get("competency");
                if (n == null) n = m.get("text");
                name = n == null ? "" : String.valueOf(n).trim();
                required = intOrNull(m.get("required"));
                if (required == null) required = intOrNull(m.get("weight"));
                if (required == null) required = intOrNull(m.get("value"));
                rawLevel = m.get("level");
            } else {
                // 退化形态：纯字符串 → 按中等要求处理
                name = String.valueOf(item).trim();
            }
            if (name.isEmpty()) continue;
            if (required == null) required = requiredFromLevelText(rawLevel); // 旧格式/英文等级兜底
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("required", Scores.clamp(required, Scores.STD_FLOOR, Scores.STD_CEIL));
            row.put("level", Scores.levelOf((Integer) row.get("required")));
            out.add(row);
        }
        // 排位 + 拉阶梯：按要求强度从高到低排，且相邻至少差 6 分，避免出现等长条
        rankAndSpread(out);
        return out;
    }

    /**
     * 标准表「排位」：按要求强度从高到低排序，再把相邻分值拉开至少 {@link Scores#STD_GAP} 分。
     * 模型经常给出一串挤在一起的高分（如 90/88/86/85），画出来几条能量条几乎一样长、像模板。
     * 被微调的条目保留 {@code requiredRaw} 与 {@code adjusted} 以便追溯。
     */
    private static void rankAndSpread(List<Map<String, Object>> items) {
        if (items.isEmpty()) return;
        // 排位：要求高的在前（同分按下标，结果可复现）
        items.sort((a, b) -> Integer.compare((Integer) b.get("required"), (Integer) a.get("required")));

        int n = items.size();
        int[] raw = new int[n];
        for (int i = 0; i < n; i++) raw[i] = (Integer) items.get(i).get("required");
        int[] spread = Scores.spread(raw, Scores.STD_GAP, Scores.STD_FLOOR, Scores.STD_CEIL);
        boolean[] moved = Scores.adjusted(raw, spread);
        for (int i = 0; i < n; i++) {
            if (!moved[i]) continue;
            Map<String, Object> it = items.get(i);
            it.put("requiredRaw", raw[i]);
            it.put("required", spread[i]);
            it.put("level", Scores.levelOf(spread[i]));
            it.put("adjusted", true);
        }
    }

    /** 旧格式（高/中/基本，或英文 high/low）→ 一个合理的数值，保证老记录与新记录同形 */
    private static int requiredFromLevelText(Object v) {
        String s = asString(v).trim().toLowerCase();
        if (s.isEmpty()) return 62;
        if (s.contains("极高") || s.contains("critical")) return 93;
        if (s.contains("高") || s.contains("high") || s.contains("must")) return 86;
        if (s.contains("偏高")) return 76;
        if (s.contains("偏低")) return 50;
        if (s.contains("基本") || s.contains("基础") || s.contains("basic") || s.contains("nice")) return 38;
        if (s.contains("极低")) return 24;
        if (s.contains("低") || s.contains("low")) return 30;
        return 62;
    }

    /** 数字字段兜底解析：模型可能给 "88"、88.0、"88 分" */
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

    /** 能力等级 → CSS 类名后缀（避免中文类名）。七档归到三档配色：高绿 / 中黄绿 / 低浅土黄 */
    private static String lvClass(String level) {
        String s = asString(level);
        if (s.contains("高")) return "hi";
        if (s.contains("低")) return "base";
        return "mid";
    }

    /** 标准表：每条能力一条能量条（0-100 的要求强度），后面测评环节会拿它跟候选人逐条对比 */
    private static String levelChipsHtml(List<Map<String, Object>> items) {
        if (items == null || items.isEmpty()) {
            return "<p style=\"font-size:12.5px;color:var(--mut);margin-top:6px\">— 无 —</p>";
        }
        StringBuilder sb = new StringBuilder("<div class=\"std\">");
        for (Map<String, Object> it : items) {
            int required = intOrNull(it.get("required")) == null ? 60 : intOrNull(it.get("required"));
            String level = asString(it.get("level"));
            sb.append("<div class=\"std-row\">")
                    .append("<span class=\"std-name\">").append(esc(asString(it.get("name")))).append("</span>")
                    .append("<span class=\"std-track\"><i class=\"std-fill ").append(lvClass(level))
                    .append("\" style=\"width:").append(required).append("%\"></i></span>")
                    .append("<span class=\"std-val\">").append(required).append("</span>")
                    .append("<span class=\"std-lv\">").append(esc(level)).append("</span>")
                    .append("</div>");
        }
        return sb.append("</div>").toString();
    }

    /** 单行超过这个长度，才考虑二次断行（与前端 MAX_LINE 保持一致） */
    private static final int MAX_LINE = 60;

    /**
     * 任务背景（澄清诉求）：按句拆行，首句作导语。
     * 与前端同一套规则：
     *   1. 只在「整句结束」处断（。！？ + 换行），不按分号/逗号断——否则会被切得很碎；
     *   2. 只有整行超过 60 字、且行内有 2 个以上分号（「键：值；键：值」那种并列形态）时，
     *      才在分号处再断一次，避免出现 150 字一整行的"墙"。
     */
    private static String missionHtml(String text) {
        String s = text == null ? "" : text.trim();
        if (s.isEmpty()) {
            return "<p class=\"mline\">— 暂未澄清 —</p>";
        }
        List<String> lines = new ArrayList<>();
        for (String raw : s.split("(?<=[。！？\\n])")) {
            String t = raw.trim();
            if (t.isEmpty()) {
                continue;
            }
            // 只有标点的碎片并回上一段，避免出现「<p>。</p>」这种孤零零的一段
            if (t.matches("[。！？]+") && !lines.isEmpty()) {
                lines.set(lines.size() - 1, lines.get(lines.size() - 1) + t);
                continue;
            }
            if (t.length() > MAX_LINE && countOf(t, '；') >= 2) {
                for (String piece : t.split("(?<=；)")) {
                    String p = piece.trim();
                    if (!p.isEmpty()) {
                        lines.add(p);
                    }
                }
            } else {
                lines.add(t);
            }
        }
        if (lines.isEmpty()) {
            lines.add(s);
        }
        boolean multi = lines.size() > 1;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            sb.append("<p class=\"mline").append(multi && i == 0 ? " lead" : "").append("\">");
            if (multi) {
                sb.append("<i class=\"mno\">").append(String.format("%02d", i + 1)).append("</i>");
            }
            sb.append("<span class=\"mtx\">").append(esc(lines.get(i))).append("</span></p>");
        }
        return sb.toString();
    }

    /** 统计某字符出现次数 */
    private static int countOf(String s, char c) {
        int n = 0;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) == c) {
                n++;
            }
        }
        return n;
    }

    /** 编号列表（DeepSeek 式「1. 2. 3.」）；空列表给占位提示。 */
    private static String numListHtml(List<String> items) {
        if (items == null || items.isEmpty()) {
            return "<p class=\"empty\">— 暂未列出 —</p>";
        }
        StringBuilder sb = new StringBuilder("<ul class=\"num\">");
        int n = 1;
        for (String item : items) {
            sb.append("<li><i>").append(n++).append("</i><span>").append(esc(item)).append("</span></li>");
        }
        sb.append("</ul>");
        return sb.toString();
    }

    /** 标签 chips；blue=true 用蓝色系，否则绿色系。 */
    private static String chipsHtml(List<String> items, boolean blue) {
        if (items == null || items.isEmpty()) {
            return "<p class=\"empty\">— 暂未列出 —</p>";
        }
        StringBuilder sb = new StringBuilder("<div class=\"chips\">");
        String cls = blue ? "chip blue" : "chip";
        for (String item : items) {
            sb.append("<span class=\"").append(cls).append("\">")
                    .append(esc(Texts.stripTail(item))).append("</span>");
        }
        sb.append("</div>");
        return sb.toString();
    }

    /** 侧栏「仍需澄清」卡片内容：有待确认/追问就列出；都没有就提示信息已足。 */
    private static String openItemsHtml(List<String> missing, List<String> questions) {
        if ((missing == null || missing.isEmpty()) && (questions == null || questions.isEmpty())) {
            return "<p class=\"ok\">信息已足够清晰，可直接进入简历初筛 ✓</p>";
        }
        StringBuilder sb = new StringBuilder();
        if (missing != null && !missing.isEmpty()) {
            sb.append("<p class=\"mini-h\">待确认信息</p><ul class=\"plain\">");
            for (String m : missing) {
                sb.append("<li>").append(esc(m)).append("</li>");
            }
            sb.append("</ul>");
        }
        if (questions != null && !questions.isEmpty()) {
            sb.append("<p class=\"mini-h\">建议向业务方追问</p><ul class=\"plain\">");
            for (String q : questions) {
                sb.append("<li>").append(esc(q)).append("</li>");
            }
            sb.append("</ul>");
        }
        return sb.toString();
    }

    /** HTML 转义，防止内容里的特殊字符破坏页面结构。 */

    // 本地联调演示：右键运行 main，一条命令走完 A→B→C→D→E 全流程（需先配好 DEEPSEEK_API_KEY）
    // 严格按文档数据契约接力：A.jd→B，B.resume/score→C/D/E，C.minutes→D/E，D.assessment→E。
    public static void main(String[] args) {
        // ← 整条流水线唯一需要人提供的外部输入（想改就改这几行）
        String rawDemand = "招一个会做数据分析的人，能独立建报表";   // A 输入：招聘诉求
        String resume = "张三\n本科 统计学\n3年数据分析经验\n技能：SQL、Python、Tableau\n独立搭建业务报表并输出分析报告";  // B 输入：候选人简历
        String transcript = "Q1: 你做过哪些报表？\nA1: 独立搭建电商日报与活动复盘报表。\nQ2: 熟悉哪些工具？\nA2: 熟练 SQL、Python、Tableau。";  // C 输入：面试问答
        String salaryData = "数据分析师一线城市约 15-25K";   // E 输入：市场薪资基准（可留空，空则 E 走"待人工确认"）

        // ---- ① A 招聘分析师：诉求 → 需求澄清 + 结构化 JD + 能力画像 ----
        AnalystAgent analyst = new AnalystAgent();
        Map<String, Object> aIn = new LinkedHashMap<>();
        aIn.put("raw_demand", rawDemand);
        Map<String, Object> aOut = analyst.run(aIn);
        @SuppressWarnings("unchecked")
        Map<String, Object> jd = (Map<String, Object>) aOut.get("jd");
        System.out.println("[① A] JD 已生成：" + jd.get("title") + "（jd_id=" + jd.get("jd_id") + "）");

        // ---- ② B 简历猎手：A 的 jd + 简历 → 结构化简历 + 初筛打分 ----
        ScoutAgent scout = new ScoutAgent();
        Map<String, Object> bIn = new LinkedHashMap<>();
        bIn.put("jd", jd);
        bIn.put("resume", resume);
        Map<String, Object> bOut = scout.run(bIn);
        @SuppressWarnings("unchecked")
        Map<String, Object> resumeStruct = (Map<String, Object>) bOut.get("resume");
        System.out.println("[② B] 初筛完成：" + bOut.get("verdict") + " / score=" + bOut.get("score"));

        // ---- ③ C 面试官：jd + 结构化简历 + 面试问答 → 面试纪要 ----
        InterviewerAgent interviewer = new InterviewerAgent();
        Map<String, Object> cIn = new LinkedHashMap<>();
        cIn.put("jd", jd);
        cIn.put("resume", resumeStruct);
        cIn.put("transcript", transcript);
        Map<String, Object> cOut = interviewer.run(cIn);
        @SuppressWarnings("unchecked")
        Map<String, Object> minutes = (Map<String, Object>) cOut.get("minutes");
        System.out.println("[③ C] 面试纪要已生成，verdict=" + minutes.get("verdict"));

        // ---- ④ D 测评背调员：jd + 简历 + 纪要 → 技能测评 + 背调 + 文化匹配 ----
        AssessorAgent assessor = new AssessorAgent();
        Map<String, Object> dIn = new LinkedHashMap<>();
        dIn.put("jd", jd);
        dIn.put("resume", resumeStruct);
        dIn.put("minutes", minutes);
        Map<String, Object> dOut = assessor.run(dIn);
        System.out.println("[④ D] 测评完成：level=" + dOut.get("level") + " / risk=" + dOut.get("risk_level") + " / fit=" + dOut.get("fit_score"));

        // ---- ⑤ E offer 管家：综合结论 + 薪资基准 → offer 草案 + 入职计划 ----
        ConciergeAgent concierge = new ConciergeAgent();
        Map<String, Object> eIn = new LinkedHashMap<>();
        eIn.put("candidate", Map.of("candidate_id", "C-1", "name", String.valueOf(resumeStruct.get("name"))));
        eIn.put("jd", jd);
        eIn.put("resume", resumeStruct);
        eIn.put("minutes", minutes);
        eIn.put("scorecard", bOut);
        eIn.put("assessment", dOut);
        eIn.put("salary_data", salaryData);
        eIn.put("offer_gate", "pending_human_confirm");
        Map<String, Object> eOut = concierge.run(eIn);
        @SuppressWarnings("unchecked")
        Map<String, Object> offer = (Map<String, Object>) eOut.get("offer");
        System.out.println("[⑤ E] offer 已生成：position=" + offer.get("position") + " / salary=" + offer.get("salary_range"));
        System.out.println("[A→B→C→D→E] 全流程一条命令跑通；5 个 agent 的成果都在 demo/ 目录下。");
    }
}

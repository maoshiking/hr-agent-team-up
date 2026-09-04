# SKILLS 技能注册表（唯一权威 · 已整合为 5）

> 权威结论：**原 12 个细分技能已整合为 5 个——每个数字员工对应 1 个技能（= 它的 `run()` 整份活）**。
> 之所以整合：Java 实现里 5 个 agent 各只有一个 `run()`，原先"一个 agent 挂 2~3 个技能"造成错位与冗余。现在**技能数 = agent 数 = 5**，和代码完全对齐。
> 原 12 个子能力降为"内部步骤"说明，见下方映射表。本表是全部技能的唯一来源。

---

## 全流程（A→E 线性，5 个技能）

```
① 招聘需求处理 (A 招聘分析师)
      │
② 简历解析与初筛 (B 简历猎手)
      │
③ 面试 (C 面试官)
      │
④ 测评·背调·文化匹配 (D 测评背调员)
      │
⑤ offer 与入职 (E offer 管家)
```

---

## 技能总表（5 个技能 = 5 个 Agent.run()）

| # | 技能 id | 名字 | 所属数字员工 | 它的 `run()` 产出字段 | 原 12 中的内部子步骤 |
|---|---|---|---|---|---|
| ① | `talent.analyze` | 招聘需求处理 | A 招聘分析师 | `jd{title,responsibilities[],hard_requirements[],nice_to_have[]}`、`persona{core_competencies[],soft_traits[],culture_fit_hint}`、`demand`(澄清陈述/追问清单)、`reason` | demand.clarify · jd.generate · persona.build |
| ② | `resume.screen` | 简历解析与初筛 | B 简历猎手 | `resume{name,years,skills[],experiences[],education}`、`score`(0–100)、`verdict`(shortlist/hold/reject)、`matched[]{skill,evidence}`、`gaps[]`、`reason` | resume.parse · resume.score |
| ③ | `interview.run` | 面试 | C 面试官 | `plan{questions[]{text,intent},rubric[]{dimension,weight}}` 或 `minutes{summary,qa[]{question,answer,score},verdict}` | interview.plan · interview.run |
| ④ | `assess.review` | 测评·背调·文化匹配 | D 测评背调员 | `level`(expert/proficient/basic/none)、`score`(0–100)、`risk_level`(low/medium/high)、`findings[]{item,risk,evidence}`、`fit_score`(0–100)、`fills_gap[]`、`overlaps[]` | assess.skill · assess.integrity · assess.culture |
| ⑤ | `offer.onboard` | offer 与入职 | E offer 管家 | `offer{position,salary_range,suggested,clauses[]}`、`plan{week1_4[],goals[]}`、`screening_feedback` | offer.generate · onboard.plan |

> 枚举/刻度：`verdict`=shortlist/hold/reject；`level`=expert/proficient/basic/none；`risk_level`=low/medium/high；评分统一 0–100。

---

## 每个技能对应的 Java 类

| 技能 | Java 类（成员只改这个） |
|---|---|
| `talent.analyze` | `analyst/AnalystAgent.java` |
| `resume.screen` | `scout/ScoutAgent.java` |
| `interview.run` | `interviewer/InterviewerAgent.java` |
| `assess.review` | `assessor/AssessorAgent.java` |
| `offer.onboard` | `concierge/ConciergeAgent.java` |

---

## 原 12 → 新 5 映射（只看这张就不会乱）

| 原(旧)技能 | 并入 |
|---|---|
| demand.clarify / jd.generate / persona.build | → `talent.analyze` |
| resume.parse / resume.score | → `resume.screen` |
| interview.plan / interview.run | → `interview.run` |
| assess.skill / assess.integrity / assess.culture | → `assess.review` |
| offer.generate / onboard.plan | → `offer.onboard` |

---

## 说明与提醒

- **现在代码里没有"12 个技能"**，只有 5 个 agent、5 个 `run()`。各 `run()` 内部要做的多个子步骤，参考上面"内部子步骤"列即可；
- 技能间传递的数据结构（JD、Resume、Score、minutes、assessment、offer…）见 `all_skill总集成文档.md` 2.2 数据契约；
- 本文档**已取代** `DESP` 与 `all_skill` 里旧的 12 技能列表——那两处若还写 12，以本表为准。

# 成员 E · offer 与入职管家 个人手册（Java 版）

> 你专属。你只改这一个文件：**`src/main/java/com/hragent/agent/concierge/ConciergeAgent.java`**，别的都不许碰。

## 0. 一句话任务
招聘流水线**收尾**：拿 D 的测评结论 → 生成 **offer 草案与谈薪建议** → 制定 **30 天入职计划**（含反哺筛选建议）。上游 D，无下游。

## 1. 你负责的内容与输出字段
你在 `ConciergeAgent` 的 `run()` 里产出：
- `offer{ position, salary_range, suggested, clauses[] }`
- `plan{ week1_4[], goals[] }`
- `screening_feedback`（回写给筛选标准的改进建议）
字段照此，**不要自创**；薪资给区间并说明依据，不编造。

## 1.5 🎯 你的可验收成果（比赛硬要求：报告 / 表格 / 网页）

**分清两种输出**：你的 `run()` 返回的是给"下一个 agent / 管道"用的**结构化 JSON**；而**比赛要你交付的"可验收成果"是人能直接打开、判定的报告 / 表格 / 网页文件**。两者都要有，缺一不可。

| 你的成果 | 类型 | 文件格式 | 验收标准 |
|---|---|---|---|
| offer 草案 | 网页/文档 | HTML（可含 PDF）| 职位 / 薪资区间 / 建议 / 条款齐全 |
| 谈薪建议表 | 表格 | CSV | 有市场依据的区间建议 |
| 30 天入职计划 | 网页 | HTML | 有 4 周计划 + 目标 |

> 让 AI 为每个成果生成 1 份**示例文件**本地自测（先不一定要提交进仓库，能打开、内容对即可）。最后"成果工作台"会把全队的报告/表格/网页汇总成一个页面。

## 2. 给 DSH 里 agent 的专属话术（先念这句约束它）
> 我是成员 E，负责数字员工"offer 与入职管家"，只准修改文件 `agent/concierge/ConciergeAgent.java`。
> 你替我干活时：只改这一个文件，不许动 `Agent.java`、`DeepSeekClient.java`、`pom.xml`、别人/别的类、`dispatcher/` 和仓库根目录文件；需要时只读共享文件；调模型统一用 `com.hragent.common.DeepSeekClient` 的 `callJson`；不提交任何含 key 的文件。改之前把"要改的路径"念给我听。

### 填 ROLE / run() 的话术
> 请把 `ConciergeAgent.java` 里的 ROLE 写成 offer 管家提示词：说清职责；写明输出字段（上面那套）；风格"结论→依据→风险→下一步"；薪资不编造。run() 用 `DeepSeekClient.callJson`，返回前校验字段。不要改类名、方法名 run 或签名。

> 🔧 **必做（硬规定）**：ROLE 里写明本 agent 会用到的工具（如 `doc_writer`），并在 run() 里**真调用至少一个工具**把成果落成文件（.csv/.html），**不能只生成文字**就交差（详见 `文档库/02_契约与技能/工具调用规范.md`）。

## 3. 自测
1. IDEA 打开工程，配好 `DEEPSEEK_API_KEY`；
2. 右键运行 `ConciergeAgent` 的 `main`；
3. 能打印 JSON 且 `offer` 四字段完整 = 过关。

## 4. 交付（交给整合者这 4 样）
1. 改好的 `ConciergeAgent.java`；2. 你的 `ROLE` 全文；3. 1 组输入样例+期望输出；4. 一句输出字段说明。

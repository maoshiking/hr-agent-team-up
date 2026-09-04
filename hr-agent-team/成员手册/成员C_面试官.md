# 成员 C · 面试官 个人手册（Java 版）

> 你专属。你只改这一个文件：**`src/main/java/com/hragent/agent/interviewer/InterviewerAgent.java`**，别的都不许碰。

## 0. 一句话任务
招聘流水线**第三步**：拿 B 筛出的候选人和 JD → 生成**面试题纲** → 根据问答记录做**纪要并给结论**。上游 B，结果交给 D。

## 1. 你负责的内容与输出字段
你在 `InterviewerAgent` 的 `run()` 里产出（据输入可能是题纲或纪要）：
- `plan{ questions[]{text,intent}, rubric[]{dimension,weight} }`，或
- `minutes{ summary, qa[]{question,answer,score}, verdict }`
`verdict` 只能 shortlist/hold/reject。字段照此，**不要自创**。

## 1.5 🎯 你的可验收成果（比赛硬要求：报告 / 表格 / 网页）

**分清两种输出**：你的 `run()` 返回的是给"下一个 agent / 管道"用的**结构化 JSON**；而**比赛要你交付的"可验收成果"是人能直接打开、判定的报告 / 表格 / 网页文件**。两者都要有，缺一不可。

| 你的成果 | 类型 | 文件格式 | 验收标准 |
|---|---|---|---|
| 面试题纲 | 网页/文档 | HTML | 有问题列表 + 评分维度 |
| 面试纪要报告 | 报告 | HTML / Markdown | 有摘要 + 逐题打分 + 判定 |
| 面试评分表 | 表格 | CSV | 维度 × 权重 × 得分 |

> 让 AI 为每个成果生成 1 份**示例文件**本地自测（先不一定要提交进仓库，能打开、内容对即可）。最后"成果工作台"会把全队的报告/表格/网页汇总成一个页面。

## 2. 给 DSH 里 agent 的专属话术（先念这句约束它）
> 我是成员 C，负责数字员工"面试官"，只准修改文件 `agent/interviewer/InterviewerAgent.java`。
> 你替我干活时：只改这一个文件，不许动 `Agent.java`、`DeepSeekClient.java`、`pom.xml`、别人/别的类、`dispatcher/` 和仓库根目录文件；需要时只读共享文件；调模型统一用 `com.hragent.common.DeepSeekClient` 的 `callJson`；不提交任何含 key 的文件。改之前把"要改的路径"念给我听。

### 填 ROLE / run() 的话术
> 请把 `InterviewerAgent.java` 里的 ROLE 写成面试官提示词：说清职责；写明输出字段（上面那套）；verdict 只能 shortlist/hold/reject；风格"结论→依据→风险→下一步"；禁止编造。run() 用 `DeepSeekClient.callJson`，返回前校验字段。不要改类名、方法名 run 或签名。

> 🔧 **必做（硬规定）**：ROLE 里写明本 agent 会用到的工具（如 `doc_writer`），并在 run() 里**真调用至少一个工具**把成果落成文件（.csv/.html），**不能只生成文字**就交差（详见 `文档库/02_契约与技能/工具调用规范.md`）。

## 3. 自测
1. IDEA 打开工程，配好 `DEEPSEEK_API_KEY`；
2. 右键运行 `InterviewerAgent` 的 `main`；
3. 能打印 JSON 且 `verdict` 合法 = 过关。

## 4. 交付（交给整合者这 4 样）
1. 改好的 `InterviewerAgent.java`；2. 你的 `ROLE` 全文；3. 1 组输入样例+期望输出；4. 一句输出字段说明。

# 成员 A · 招聘分析师 个人手册（Java 版）

> 你专属。你只改这一个文件：**`src/main/java/com/hragent/agent/analyst/AnalystAgent.java`**，别的都不许碰。

## 0. 一句话任务
你负责招聘流水线**开头**：把模糊诉求（"招个会做数据分析的人"）变成清楚的 **JD（岗位描述）** 和 **该招什么样的人（能力画像）**。无上游，结果交给 B。

## 1. 你负责的内容与输出字段
你要在 `AnalystAgent` 的 `run()` 里产出：
- `jd{ title, responsibilities[], hard_requirements[], nice_to_have[] }`
- `persona{ core_competencies[], soft_traits[], culture_fit_hint }`
- `reason`
字段照此，**不要自创**。`AnalystAgent` 已按"参考模板"写好骨架，你补 `ROLE` 和逻辑即可。

## 1.5 🎯 你的可验收成果（比赛硬要求：报告 / 表格 / 网页）

**分清两种输出**：你的 `run()` 返回的是给"下一个 agent / 管道"用的**结构化 JSON**；而**比赛要你交付的"可验收成果"是人能直接打开、判定的报告 / 表格 / 网页文件**。两者都要有，缺一不可。

| 你的成果 | 类型 | 文件格式 | 验收标准 |
|---|---|---|---|
| 需求澄清说明 | 报告 | Markdown / HTML（网页） | 有澄清陈述 + 追问清单 |
| 结构化 JD | 网页 | HTML | 四字段完整（title / 职责 / 硬要求 / 加分）|
| 候选人能力画像 | 表格 | CSV | 三字段完整 |

> 让 AI 为每个成果生成 1 份**示例文件**本地自测（先不一定要提交进仓库，能打开、内容对即可）。最后"成果工作台"会把全队的报告/表格/网页汇总成一个页面。

## 2. 给 DSH 里 agent 的专属话术（先念这句约束它）
> 我是成员 A，负责数字员工"招聘分析师"，只准修改文件 `agent/analyst/AnalystAgent.java`。
> 你替我干活时：只改这一个文件，不许动 `Agent.java`、`DeepSeekClient.java`、`pom.xml`、别人/别的类、`dispatcher/` 和仓库根目录文件；需要时只读共享文件；调模型统一用 `com.hragent.common.DeepSeekClient` 的 `callJson`；不提交任何含 key 的文件。改之前把"要改的路径"念给我听。

### 填 ROLE / run() 的话术
> 请把 `AnalystAgent.java` 里的 ROLE 写成招聘分析师提示词：说清职责；写明输出字段（上面那套）；风格"结论→依据→风险→下一步"；禁止编造。run() 里用 `DeepSeekClient.callJson`，返回前校验字段。不要改类名、方法名 run 或签名。

> 🔧 **必做（硬规定）**：ROLE 里写明本 agent 会用到的工具（如 `doc_writer`），并在 run() 里**真调用至少一个工具**把成果落成文件（.csv/.html），**不能只生成文字**就交差（详见 `文档库/02_契约与技能/工具调用规范.md`）。

## 3. 自测（过关才算完）
1. 用 IDEA 打开工程，配好环境变量 `DEEPSEEK_API_KEY`；
2. 右键运行 `AnalystAgent` 的 `main`；
3. 控制台能打印出 JSON 且字段齐全 = 过关。

## 4. 交付（交给整合者这 4 样）
1. 改好的 `AnalystAgent.java`；2. 你的 `ROLE` 全文；3. 1 组输入样例+期望输出；4. 一句输出字段说明。

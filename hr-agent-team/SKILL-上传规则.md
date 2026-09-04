# SKILL：上传纪律（Upload Discipline）— Java 版

> 给每个成员的"上传 agent"的强制规则。让各成员各自的 DSH agent 代劳上传，但 agent 上传时**必须严格按下面规矩**，只准动自己那一个 Java 类，防止仓库错乱。
> 用法：每个成员在 DSH 里先读本文件，再把自己的角色告诉 agent（见第 3 节那句话），之后所有改动都交给 agent 按此执行。

## 1. 一句话规则

> **每次改代码前自问：我要改的 .java 文件，是我自己的那个吗？不是，就绝不改。**

## 2. 主仓库结构与"我能碰/不能碰"

主仓库根：`hr-agent-team/`，代码都在 `src/main/java/com/hragent/` 下。

| 路径（在 src/main/java/com/hragent/ 内） | 归属 | 我(成员)能碰吗 |
|---|---|---|
| `agent/analyst/AnalystAgent.java` | 成员 A | **只有 A 能改** |
| `agent/scout/ScoutAgent.java` | 成员 B | **只有 B 能改** |
| `agent/interviewer/InterviewerAgent.java` | 成员 C | **只有 C 能改** |
| `agent/assessor/AssessorAgent.java` | 成员 D | **只有 D 能改** |
| `agent/concierge/ConciergeAgent.java` | 成员 E | **只有 E 能改** |
| `agent/Agent.java`（接口） | 共享 | 只读，**不许改** |
| `common/DeepSeekClient.java` | 共享 | 可读；**改要整合者批准** |
| `HrAgentApplication.java` | 共享 | 只读 |
| `pom.xml`、`src/main/resources/` | 共享 | 只读，改动要批准 |
| 别人的 agent 类 | 别人 | **不许碰** |
| `agent/dispatcher/`（后续加） | 整合者 | **不许碰** |
| `README.md`、`SKILL-上传规则.md`、`入门说明书.md`、`成员手册/` | 共享 | 只读 |
| 仓库根目录新增任何文件 | 共享 | **禁止** |

## 3. 每个成员给 agent 说的一句话（必做）

> 我是【成员 A/B/C/D/E】，在主仓库 `hr-agent-team` 里负责数字员工【…】，代码只属于我自己的类是
> 【`agent/analyst/AnalystAgent.java` 等】。
> 你替我干活时，必须遵守：
> 1) 你**只能修改我自己那一个 .java 文件**（其他人别的类、`Agent.java`、`DeepSeekClient.java`、`pom.xml`、`HrAgentApplication.java`、`dispatcher/`、根目录文件都不许动）；
> 2) 需要参考时**只读**共享文件，不修改；
> 3) 调模型统一用 `com.hragent.common.DeepSeekClient` 的 `callJson` / `call`；
> 4) **不提交任何含 API key 的文件**（key 只放本机环境变量）；
> 5) 每次改代码前，把"我要改的路径"念一遍给我听，确认是我自己的类才动手。

## 4. agent 改代码前的"自检五连问"

1. 我要改的路径，是不是我自己那一个 `.java`？
2. 有没有动 `Agent.java`、`DeepSeekClient.java`、`pom.xml`、`HrAgentApplication.java`、别人的类、`dispatcher/`、仓库根目录？→ 有就**取消**
3. 是不是在改共享的契约 / README / 手册？→ 是就**取消**
4. 代码里有没有会泄露的 key？→ 有就**取消并提醒**
5. 是不是乱加了别的顶层文件？→ 有就**清理或问整合者**

## 5. 命名与格式约定

- 你只改类名固定为：`AnalystAgent` / `ScoutAgent` / `InterviewerAgent` / `AssessorAgent` / `ConciergeAgent`；
- `package` 和文件路径固定，不要改包名、不要改方法名 `run`、不要改签名；
- 输出必须是合法 JSON（Map），字段照 `SKILLS技能注册表.md`，不自创。

## 6. 违反的后果

- 改错文件 → 编译失败 / 覆盖别人成果 → 要整合者手动修，很麻烦；
- 传了 key → 泄漏风险，**绝不发生**；
- 改了共享接口 / pom → 全员对不上 → 返工。

> 一句话记住：**只改我自己那一个 Java 类，其它只读，绝不越界。**

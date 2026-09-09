# hr-agent-team（Java / Spring Boot）

> 5 个数字员工协同招聘项目。**后端统一用 Spring Boot（Java）**，agent 也用 Java 写，后面直接接入，不返工。
> 上传必须遵守 `SKILL-上传规则.md`；新手先看 `入门说明书.md`。

## 这是什么工程

一个标准 **Maven + Spring Boot** 工程（Java 17）。每个数字员工 = 一个实现 `Agent` 接口的类（`run(Map payload) → Map`，吃进 JSON、吐出 JSON）。

## 目录结构与归属（谁只准碰哪一格）

```
hr-agent-team/
├── pom.xml                                   共享·只读（构建配置）
├── README.md / SKILL-上传规则.md / 入门说明书.md / 成员手册/   共享·只读
└── src/main/java/com/hragent/
    ├── HrAgentApplication.java                共享·启动入口
    ├── common/DeepSeekClient.java             共享·全项目唯一"调模型 + 调工具"类
    ├── agent/
    │   ├── Agent.java                          共享·接口（不要改）
    │   ├── analyst/AnalystAgent.java           成员 A 专属：招聘分析师
    │   ├── scout/ScoutAgent.java               成员 B 专属：简历猎手
    │   ├── interviewer/InterviewerAgent.java   成员 C 专属：面试官
    │   ├── assessor/AssessorAgent.java         成员 D 专属：测评背调员
    │   └── concierge/ConciergeAgent.java       成员 E 专属：offer 与入职管家
    └── dispatcher/                             (整合者后续写) 按顺序串联 5 个 agent
```

## 归属速查

| 成员 | 数字员工 | 只准编辑这个文件 |
|---|---|---|
| A | 招聘分析师 | `agent/analyst/AnalystAgent.java` |
| B | 简历猎手 | `agent/scout/ScoutAgent.java` |
| C | 面试官 | `agent/interviewer/InterviewerAgent.java` |
| D | 测评背调员 | `agent/assessor/AssessorAgent.java` |
| E | offer 与入职管家 | `agent/concierge/ConciergeAgent.java` |

## 怎么跑

1. 用 **IDEA** 打开本目录（自动按 `pom.xml` 拉依赖）；
2. 先配环境变量 `DEEPSEEK_API_KEY`；
3. 每个 agent 自带一个 `main()` 可自测：右键运行你的类，能打印出 JSON 就过关。

## 三条铁律

1. 调模型只用 `common/DeepSeekClient` 的 `callJson` / `call`，不自己另写；
2. 每个 agent 只做 `吃进 JSON → 吐出 JSON`，字段照 `SKILLS技能注册表.md`，不自创；
3. 上传代码只准进自己那个 `.java` 文件，禁止改共享文件 / 别人的类 / `pom.xml`；
4. **每个 agent 必须真调用至少一个工具**（把成果落成 .csv/.html 文件），不只生成文字——详见《工具调用规范》。

> 契约文档（DESP / all_skill / SKILLS技能注册表 等）在 `D:\编程练习册\Agent_Create\文档库\02_契约与技能\`，属只读参考区。入口先看 `文档库\00_文档总目录.md`。

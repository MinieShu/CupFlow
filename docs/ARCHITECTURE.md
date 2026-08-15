# CupFlow Agent 架构说明

## 范围

CupFlow 是面向奶茶店单工位制作流程的 AI 眼镜工作流 Agent 原型。它只提供流程辅助、风险提示和复核证据；不控制设备、不替代食品安全判断、不接入真实 POS、支付、库存或人员绩效系统。

## 任务闭环

```text
订单输入 → 配方检索 → 第一视角视觉感知 → LangGraph 策略判断
       → 语音/轻量提示 → 店员纠正或人工复核 → 结果与 Trace 交付
```

## 核心模块

| 模块 | 实现 | 作用 |
| --- | --- | --- |
| RAG 检索链路 | `lib/cupflow-agent.ts` | 从版本化的模拟配方知识库检索步骤、定制约束与安全规则，并将来源写入 Trace。 |
| Tool Calling | `load_order_context`、`retrieve_recipe_knowledge`、`evaluate_workflow_policy` | 将订单读取、知识检索和策略判断拆分为可审计工具。 |
| LangGraph 工作流 | `StateGraph` | 固定编排“加载上下文 → 检索 → 策略评估”，不由模型自由决定流程。 |
| 视觉感知 | `/api/vision` | 调用兼容 OpenAI 的视觉模型接口，返回结构化事件与杯贴字段。 |
| 安全策略 | `evaluateObservation` | 默认不推进；低置信度保持当前步骤；超量和杯贴不匹配必须人工复核。 |
| 上下文管理 | 会话 ID、当前订单、当前步骤、最近事件 | 仅传递完成当次决策所需的最小上下文；本版本不做跨会话持久化。 |
| Trace | `traceId`、工具列表、知识来源、策略结果、模型耗时与 token 用量 | 用于 Demo 复盘和评测，不包含原始图像或 API Key。 |

## 模型边界

视觉模型只负责从画面提取候选事实。是否推进订单、是否视为异常、是否要求复核，均由 CupFlow 的确定性策略与配方约束决定。

## 外部依赖

- `@langchain/langgraph`：工作流状态图。
- 兼容 OpenAI Chat Completions 的视觉模型服务：视觉感知；示例为阿里云百炼 Qwen VL。
- 浏览器 MediaDevices 与 SpeechSynthesis：第一视角采集和语音播报；SpeechRecognition 仅作为可选语音指令增强。

## 部署接口

- `POST /api/vision`：接收单帧图像，输出结构化视觉观察结果。
- `POST /api/agent/decision`：接收订单、当前步骤和视觉观察，输出 Agent 决策及 Trace。
- `GET /api/agent/evaluate`：运行确定性策略评测套件。

生产部署时应将视觉模型 Key 放入部署平台的服务端 Secret，不得写入浏览器、仓库或演示视频。

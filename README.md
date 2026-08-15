# 杯序 CupFlow

CupFlow 是一套面向奶茶门店制作场景的流程辅助系统。它接收第一视角画面和订单信息，结合配方规则判断当前操作是否符合要求，在需要时给出简短提示，并记录可复盘的异常事件。

产品面向 Android AI 眼镜设计；仓库同时提供 Web 控制台和 Android 采集端，便于开发、演示和设备联调。

## 能做什么

- 根据订单引导取杯、加茶底、加小料、定量、封杯与杯贴核验等步骤。
- 识别错料、重复加料、超量、杯贴不匹配等情况；低置信度时不自动放行。
- 店员纠正后，系统在连续观察到正确操作时继续流程，避免重复确认。
- 返回本次判断的规则依据、耗时与事件记录，供培训和复盘使用。
- 为多订单场景提供派单建议，也可将空闲员工自动分配给新订单。

当前内置的示例订单为：`A102｜云朵乌龙奶茶｜少糖｜去冰｜加珍珠`。

## 工作方式

```text
第一视角画面 + 订单
          ↓
视觉识别 → 配方检索 → 流程规则判断
          ↓
语音/文字提示、人工复核或进入下一步
```

视觉模型只负责提取画面中的候选信息；是否推进流程、是否报警，均由订单和配方规则决定。这样能避免单次识别结果直接改变订单状态。

## 快速开始

### 环境

- Node.js 22.13 或更高版本

### 配置与启动

```bash
npm install
cp .env.example .env.local
npm run dev
```

在 `.env.local` 中填写兼容 OpenAI Chat Completions 的视觉服务配置：

```dotenv
VISION_API_BASE_URL=https://your-provider.example/compatible-mode/v1
VISION_API_KEY=your_server_side_key
VISION_MODEL=qwen3-vl-flash
```

打开 [http://localhost:3000](http://localhost:3000)，授权摄像头后开始订单。API Key 只由服务端读取；不要提交 `.env.local`。

### 构建检查

```bash
npm run build
```

## Android AI 眼镜采集端

`android-glasses/` 是一个标准 Android 工程：它通过 CameraX 获取关键帧，调用 CupFlow 服务端，再以短文字和中文语音反馈结果。模型 Key 不会写入 APK。

构建、安装和设备适配说明见 [Android 采集端 README](android-glasses/README.md)。

## 项目结构

```text
app/                       # Web 控制台与 API 路由
lib/cupflow-agent.ts       # 订单、配方与流程判断
lib/dispatch.ts            # 多订单派单逻辑
android-glasses/           # Android AI 眼镜采集端
electron/                  # Windows 便携桌面版入口
docs/                      # 架构、合规、评测与派单说明
```

## 使用边界

- 仅使用经授权的制作台画面和演示订单；不采集顾客人脸、支付信息或真实订单隐私。
- 系统不控制门店设备，也不替代门店 SOP、食品安全制度或店员的最终判断。
- 超量和杯贴不匹配等高风险情况需要人工复核。

## 相关文档

- [系统架构](docs/ARCHITECTURE.md)
- [数据与合规](docs/COMPLIANCE.md)
- [评测与成本](docs/EVALUATION.md)
- [多人派单](docs/DISPATCH.md)
- [MIT License](LICENSE)

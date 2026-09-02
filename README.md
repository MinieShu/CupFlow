# 杯序 CupFlow

CupFlow 是面向奶茶门店制作场景的 AI 眼镜流程辅助系统。店长在手机端扫描杯贴或下发订单，眼镜端以最少文字提示当前步骤；手机端持续接收第一视角关键帧，由视觉服务结合配方与门店物料格架判断操作是否完成、是否异常，并在纠正后继续流程。

## 核心能力

- **订单到眼镜**：店长端可录入、扫描杯贴识别并下发订单；高置信度订单可自动下发，眼镜空闲时也可扫描杯贴开始。
- **配方与步骤编排**：手机端以卡片管理饮品配方和图片；支持导入文本生成步骤。订单中的珍珠、椰果等小料会插入到合适制作位置，而不是统一放在最后。
- **极简眼镜提示**：眼镜端使用不透明黑底、沉浸式全屏、常亮屏和三行字幕，只显示订单、当前步骤与必要提醒；可说“开始制作”或轻触开始，收到异常后显示红色提示并播报。
- **实时流程识别**：手机 Companion 通过 CXR-L 获取第一视角关键帧，调用后端视觉服务，识别取杯、加茶底、加小料、定量、扣紧杯盖与杯贴核验等事件。
- **格架兜底判断**：独立物料依靠标签直接识别；小料放在固定分格架时，店长可配置多个格架、格数和每格物料，并可选拍摄基准图。物料外观不清晰但格位可靠时，视觉服务可按格位辅助判断。
- **异常留证与继续制作**：异常前后关键帧在手机本地按“时间 + 订单号 + 饮品 + 操作者”归档；店长记录异常后，已开始的订单会恢复自动识别。

## 架构

```text
店长手机（订单 / 配方 / 格架 / 异常）
              │ CXR
              ▼
AI 眼镜（最少字幕、TTS、订单状态）
              │ 第一视角关键帧
              ▼
手机 Companion（取帧、节流、异常证据）
              │ HTTP / HTTPS
              ▼
CupFlow 视觉服务（视觉模型 + 配方规则 + 格架上下文）
              │
              └──────── 识别结果与异常提示回传眼镜
```

视觉模型只负责从画面提取候选操作；是否推进流程、是否提示异常由订单和配方规则共同决定。低置信度不自动放行，高风险情况由店员最终确认。

## 目录

```text
app/                       # Next.js 店长 Web 控制台与视觉 API
lib/                       # 订单、配方、流程判断与派单逻辑
android-glasses/           # 早期 Android 眼镜采集端工程
android-native/            # 当前原生 Kotlin 工程：眼镜端 app + 手机 Companion
docs/project/              # 项目说明、测试流程、运行说明、合规与现场记录模板
electron/                  # Windows 便携桌面版入口
```

## Web 视觉服务

### 环境

- Node.js 22.13 或更高版本

### 启动

```bash
npm install
cp .env.example .env.local
npm run dev
```

在 `.env.local` 中填入兼容 OpenAI Chat Completions 的视觉服务配置：

```dotenv
VISION_API_BASE_URL=https://your-provider.example/compatible-mode/v1
VISION_API_KEY=your_server_side_key
VISION_MODEL=qwen3-vl-flash
```

打开 [http://localhost:3000](http://localhost:3000) 可使用 Web 控制台。视觉 Key 仅由服务端读取；不要将 `.env.local` 纳入 Git。

构建检查：

```bash
npm run build
```

## Android 原生工程

`android-native/` 是当前演示使用的 Kotlin 多模块工程：

- `:app`：Rokid 眼镜端。接收订单与流程状态，显示最少文字提示并以中文 TTS 播报。
- `:companion`：店长手机端。提供眼镜管理、订单扫描/下发、配方管理、物料格架管理和异常管理；通过 CXR-L 获取眼镜画面。

### 构建

使用 Android Studio 打开 `android-native/`，选择 JDK 17；或在目录内运行：

```bash
./gradlew :app:assembleDebug :companion:assembleDebug
```

产物位置：

```text
app/build/outputs/apk/debug/app-debug.apk                 # 眼镜端 APK
companion/build/outputs/apk/debug/companion-debug.apk     # 店长手机端 APK
```

### 本地 USB 联调

1. 在手机 Rokid AI App 中开启眼镜 ADB，使用 Rokid 专用磁吸数据线连接眼镜。
2. 安装 `:companion` 到店长手机，在 Rokid AI App 导入 `:app` 生成的眼镜 APK。
3. 启动本仓库的 `npm run dev`，手机通过 USB 反向端口访问本机服务：

   ```bash
   adb reverse tcp:3000 tcp:3000
   ```

4. 打开眼镜端应用；手机端会开始接收关键帧。下发一个订单后，点击眼镜上的“开始制作”进入自动识别闭环。

店长端默认使用 `http://127.0.0.1:3000/api/vision`，配合 `adb reverse` 进行 USB 本地调试。可在“运行状态 → 配置视觉服务地址”中切换为部署地址；非本机地址必须使用 HTTPS。

联调时建议先用固定机位、真实杯贴、分格小料盒与带标签的独立物料完成一次完整流程。

## 数据与使用边界

- 仅在店员知情并授权的区域采集制作台画面；避免采集顾客人脸、支付信息和无关区域。
- 异常证据保存在手机本地，保留、导出和删除策略由门店决定；正式部署前应设定保留期限与访问权限。
- 系统不控制门店设备，不替代 SOP、食品安全制度或店员的最终判断。
- 对错料、杯贴不匹配、液位异常等情况，系统提示后由人员确认并纠正。

## 相关文档

- [系统架构](docs/ARCHITECTURE.md)
- [数据与合规](docs/COMPLIANCE.md)
- [评测与成本](docs/EVALUATION.md)
- [多人派单](docs/DISPATCH.md)
- [项目资料](docs/project/README.md)
- [MIT License](LICENSE)

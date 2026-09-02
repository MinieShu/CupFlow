# CupFlow 工程运行说明

## 代码位置

- 代码仓库：<https://github.com/MinieShu/CupFlow>
- Web 与视觉服务：仓库根目录。
- 原生 Android 工程：`android-native/`，包含眼镜端 `app/` 和店长端 `companion/`。

不要将 `.env.local`、API Key、`local.properties` 或本机构建缓存纳入 Git。

## 启动视觉服务

```bash
npm install
cp .env.example .env.local
npm run dev
```

在 `.env.local` 中配置视觉服务地址、模型和 API Key。API Key 仅在服务端环境变量中使用，不进入 Android APK 或 Git 仓库。

生产构建检查：

```bash
npm run build
```

## 构建 Android 应用

使用 Android Studio 打开 `android-native/` 并选择 JDK 17，或执行：

```bash
cd android-native
JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home" ./gradlew :app:assembleDebug :companion:assembleDebug
```

APK 位置：

```text
app/build/outputs/apk/debug/app-debug.apk
companion/build/outputs/apk/debug/companion-debug.apk
```

## USB 联调

1. 在手机的 Rokid AI App 中开启眼镜 ADB，用 Rokid 专用磁吸数据线连接眼镜。
2. 安装 `companion-debug.apk` 到店长手机。
3. 让手机通过 USB 访问 Mac 本地视觉服务：

   ```bash
   adb reverse tcp:3000 tcp:3000
   ```

4. 将眼镜端 APK 放入手机 Download 目录，并在 Rokid AI App 中导入：

   ```bash
   adb push app/build/outputs/apk/debug/app-debug.apk /sdcard/Download/CupFlow-0.1.0-debug.apk
   ```

5. 打开眼镜端应用，在手机端配置眼镜、配方和格架后下发订单。

眼镜端默认以沉浸式黑底显示；订单待开始时允许说“开始制作”，也可轻触文字开始。语音识别调用的是眼镜端应用申请的眼镜麦克风权限；若设备未提供系统语音识别服务，轻触入口仍可使用。

## 订单接入与运行诊断

店长端除扫描杯贴外，还提供“导入 POS / JSON 订单”入口。当前适配结构如下，便于 POS Webhook、导出文件或中间服务转换后导入：

```json
{
  "id": "A102",
  "drink": "云朵乌龙奶茶",
  "options": ["少糖", "去冰", "加珍珠"]
}
```

导入成功后仍需由店长确认下发；缺少 `id` 或 `drink`、或 JSON 格式不正确时，当前订单不会被覆盖。

“运行状态”卡片会显示眼镜连接、关键帧、视觉服务和自动识别状态，并可配置视觉服务地址。默认地址为 `http://127.0.0.1:3000/api/vision`，用于 USB 反向端口；非本机地址必须使用 HTTPS。“识别决策日志”保存在手机本机，记录每次识别的事件、置信度、识别来源、耗时和处理结果；服务或连接异常时，当前步骤保持不变，可在恢复连接后重新下发订单或恢复识别。

## 当前构建状态

| 验证项 | 状态 |
| --- | --- |
| `npm run build` | 已通过 |
| `:app:assembleDebug` | 已通过 |
| `:companion:assembleDebug` | 已通过 |

实际运行效果会受光线、遮挡、容器外观、网络与设备连接影响。低置信度应保持当前步骤并由人员核验。

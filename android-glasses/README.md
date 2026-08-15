# CupFlow Glasses Android 采集端

这是面向通用 Android 眼镜的原生采集端，不含模型 Key。它调用现有 CupFlow 服务端的 `/api/vision` 与 `/api/agent/decision`，并将结果以短文字和中文语音反馈给佩戴者。

## 当前能力

- 后置摄像头第一视角预览与手动关键帧上传。
- 眼镜端仅显示当前结果，视觉模型和 LangGraph 运行在 CupFlow 服务端。
- Android TextToSpeech 语音播报风险或下一步提示。
- 可运行于 Android 模拟器、普通 Android 手机或兼容 Android 的眼镜设备。

## Android Studio 构建

1. 安装 Android Studio，确保 Android SDK 36 与 JDK 17（或 Android Studio 自带 JDK）可用。
2. 在 Android Studio 中打开 `android-glasses/`。
3. 等待 Gradle 同步后运行 `app`。
4. 首次启动授权相机与麦克风。
5. 填写 CupFlow 服务地址：模拟器使用 `http://10.0.2.2:3000`；真实眼镜使用运行 CupFlow 服务电脑在同一局域网的 `http://电脑局域网IP:3000`。

命令行构建调试 APK：

```bash
./gradlew assembleDebug
```

产物位于 `app/build/outputs/apk/debug/app-debug.apk`。调试版仅用于演示和设备联调。

## Rokid 与 Android 兼容策略

本工程只使用标准 Android CameraX、网络、TTS 与权限 API，不依赖某个厂商 SDK。对可安装 Android APK 的 Rokid/Android 眼镜，可先直接验证；若具体设备采用厂商专有的摄像头、显示或输入 SDK，只在 `android-glasses` 采集适配层替换实现，CupFlow 服务端无需改动。

## 安全边界

- API Key 只存在于 CupFlow 服务端环境变量，绝不写入 APK。
- 关键帧由用户主动触发；本版本不在眼镜端连续保存视频。
- `usesCleartextTraffic` 仅为本地局域网演示而开启；正式公网部署必须使用 HTTPS 并关闭明文流量。

## 部署与实机验证

- 无实机时，可先在 Android 模拟器或普通 Android 手机验证采集、服务调用与中文 TTS。
- 部署到眼镜前，确认具体型号支持标准 Android APK、CameraX、网络和 TTS；若使用厂商专有 SDK，只需替换 `MainActivity` 的摄像头、显示或输入适配层，`AgentClient` 与服务端 API 保持不变。
- 正式上线应启用 HTTPS、服务端身份认证、设备注册与短期访问令牌；以实际型号完成摄像头方向、视场角、网络延迟与语音能力的回归测试。

# AI 眼镜部署说明

## 推荐拓扑

```text
Android / Rokid 眼镜 APK
  摄像头关键帧、短文字、TTS
              │ HTTPS / 局域网 HTTP（仅演示）
              ▼
CupFlow Agent 服务
  视觉模型、LangGraph、配方检索、派单与 Trace
```

眼镜端不保存视觉模型 API Key，也不承担配方判断或派单决策。这样可限制设备端泄露风险，并让 Android 眼镜与未来 Rokid 适配共享同一 Agent 服务。

## 无实机开发流程

1. 在 Android Studio 打开 `android-glasses/`，使用 Android 模拟器或手机构建运行。
2. 启动 CupFlow 服务，并让眼镜/手机与电脑处于同一局域网。
3. 将采集端地址从模拟器默认值 `http://10.0.2.2:3000` 改为 `http://电脑局域网IP:3000`。
4. 授权摄像头后，使用“分析当前关键帧”验证服务端视觉与 Agent 决策。
5. 拿到眼镜后，确认其是否支持标准 Android APK、CameraX、网络和 TTS；若支持，直接安装验证。

## Rokid 接入原则

- 不假设某一 Rokid 型号一定支持 APK 安装或标准 CameraX。
- 若设备提供标准 Android 兼容层，本工程可作为基础采集端。
- 若设备需要厂商 SDK，仅替换 `MainActivity` 的摄像头预览、输入或显示层；`AgentClient` 和服务端 API 保持不变。
- 赛事材料应表述为“已完成 Android 眼镜适配工程；Rokid 实机部署待设备与 SDK 权限确认”，不能表述为已完成未经验证的实机部署。

## 上线前安全要求

- 生产环境使用 HTTPS，删除 `usesCleartextTraffic=true`。
- 使用服务端身份认证、设备注册和短期访问令牌保护 API。
- 按门店授权与隐私告知处理摄像头数据。
- 以真实设备型号、摄像头方向、视场角、网络延迟和 TTS 可用性完成回归测试。

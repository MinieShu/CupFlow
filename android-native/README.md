# CupFlow 原生 Android 工程

本工程用于 CupFlow 的手机—AI 眼镜联调，包含两个模块：

- `:app`：Rokid 眼镜端。黑底常亮的最少字幕界面，接收订单、步骤和异常提醒；支持中文 TTS。
- `:companion`：店长手机端。负责通过 CXR-L 接收眼镜第一视角关键帧、调用 CupFlow 视觉服务，并提供订单、配方、物料格架和异常管理。

## 准备

1. 安装 Android Studio，使用 JDK 17。
2. 在手机 Rokid AI App 中开启眼镜 ADB。
3. 使用 Rokid 专用磁吸数据线连接眼镜磁吸数据口与 Mac；普通充电线无法用于调试。
4. 用 Android Studio 打开本目录，等待 Gradle 同步。

## 构建

```bash
./gradlew :app:assembleDebug :companion:assembleDebug
```

生成的 APK：

```text
app/build/outputs/apk/debug/app-debug.apk
companion/build/outputs/apk/debug/companion-debug.apk
```

## 联调步骤

1. 将 `companion-debug.apk` 安装到店长手机。
2. 在手机 Rokid AI App 导入 `app-debug.apk`，再在眼镜端打开 CupFlow。
3. 启动 CupFlow Web 服务，并为 USB 调试手机建立端口反向：

   ```bash
   adb reverse tcp:3000 tcp:3000
   ```

4. 在手机端配置眼镜名称、配方和物料格架；可选拍摄格架基准图。
5. 扫描或录入一个订单并下发。眼镜端显示订单与第一步，点击“开始制作”后进入自动识别。
6. 手机端对格架小料、独立标签物料和操作步骤进行视觉判断；异常会留存关键帧，并将提醒回传眼镜。

## 注意

- 视觉模型密钥只配置在 Web 服务端，不能写入 APK 或提交到仓库。
- 本地 USB 联调可使用 `adb reverse`；跨网络部署时应将 `VisionClient` 改为受保护的 HTTPS 服务地址。
- 眼镜端不会自动跳转到下一订单；当前设计是一次处理一个订单，完成后由店长端下发下一单。

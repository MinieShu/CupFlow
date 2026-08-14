# CupFlow Windows 免安装版设计

## 目标

为杯序 CupFlow 提供 Windows 10/11 64 位离线演示包。使用者无需安装 Node.js，无需命令行，也不依赖外部网页。

## 交付物

- `CupFlow.exe`：Electron 便携应用，双击启动。
- `一键启动CupFlow.bat`：启动同目录应用。
- `使用说明.txt`：摄像头授权与 SmartScreen 提示处理说明。

## 方案

Electron 内置 Chromium 与 Node 运行时；主进程启动仅监听本机的 CupFlow 服务后再载入界面。现有页面、摄像头选择、订单步骤、异常警报与自动纠错保持不变。压缩包预计为 180–250MB。

## 验收

在未安装 Node.js 的 Windows 10/11 64 位电脑上，解压后双击 `.bat` 或 `.exe` 能打开界面并选择摄像头；关闭应用后不保留后台服务。

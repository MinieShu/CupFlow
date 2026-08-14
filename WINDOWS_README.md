# 杯序 CupFlow：Windows 使用说明

## 首次使用

1. 从 [Node.js 官方网站](https://nodejs.org/) 安装 **Node.js 22.13 或更高版本**（安装完成后可使用默认选项）。
2. 解压整个 CupFlow-Windows.zip 文件夹，不要只解压其中某个文件。
3. 双击 START_CUPFLOW_WINDOWS.bat。
4. 第一次启动会自动安装依赖，完成后浏览器会打开 http://localhost:3000/。

## 国内网络

如果无法访问 nodejs.org，可在 [npmmirror 的 Node 镜像目录](https://npmmirror.com/mirrors/node/) 选择 Windows x64 的安装包；安装完成后再运行启动脚本。

## 演示

1. 在 Chrome 中点击“连接摄像头”，选择电脑摄像头或 Camo / iVCam 等 iPhone 虚拟摄像头。
2. 点击“开始订单”。
3. 将当前原料移动到镜头中央的识别区。系统报警后，完成对应纠正动作即可自动进入下一步。

## 关闭

关闭浏览器后，再关闭标题为 “CupFlow Server” 的黑色命令窗口。

## 常见问题

- 浏览器没有自动打开：在 Chrome 地址栏输入 http://localhost:3000/。
- 首次启动失败：确认 Node.js 已安装、网络可用，然后再次双击启动脚本。
- 手机画面未出现：先在 Windows 安装并打开 Camo Studio 或 iVCam，再在网页中选择对应摄像头。

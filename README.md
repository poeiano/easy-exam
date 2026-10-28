# easy exam

Windows 截图副端与 Android 笔试练习主端。电脑捕获题目并发送到手机，手机管理题图、记录、解答和追问。

当前版本：Windows **0.4.4**，Android **0.4.8**。

下载：[公开安装包与校验文件](https://github.com/poeiano/easy-exam/releases/latest)。Android 下载包为不含预填模型密钥的测试包，安装后需自行配置模型服务。

## 功能

- 二维码或配对链接连接电脑，一次配对后保存设备信任，断线自动重连。
- 普通截图默认 **Alt + S**，支持显示器选择、分辨率与质量设置、暂停截图。
- **双击 Ctrl** 开始滚动长截屏，手动缓慢向下滚动，再次双击 Ctrl 拼接并发送。见 [长截屏说明](docs/DESKTOP-LONG-CAPTURE-0.4.3.md)。
- Windows 关闭窗口后驻留托盘，托盘图标采用透明背景圆角设计。
- Android 按笔试归档题图、裁剪图、答案及追问，可继续历史记录并导出 Markdown。
- 支持兼容 `chat/completions` 的视觉模型接口，以及 Markdown、LaTeX 数学公式和代码显示。

## 使用

1. 在 Windows 启动电脑端，让手机与电脑的网络互通。
2. Android 扫描电脑二维码，或粘贴电脑端复制的配对链接。
3. 在手机设置中填写模型地址、API Key 和支持图片的模型名称。
4. 手机新建笔试记录，或对已有记录选择“继续作答”。
5. 电脑收起到托盘后，使用 Alt + S 普通截图或双击 Ctrl 长截屏。

程序没有内置公共模型密钥。模型配置和笔试记录保存在本机；需要自行提供支持图片的模型服务。当前没有互联网跨网穿透功能。

长截屏需要相邻画面保留足够重叠。稀疏文字、重复布局或切换页面可能导致匹配失败，请缓慢滚动并保持同一页面。Ctrl 组合键已排除，Ctrl 加鼠标操作尚未单独过滤；其他软件使用同一双击手势时可能同时响应。

## 从源码运行 Windows 端

需要 Windows、Node.js 24，以及 Windows .NET Framework C# 编译器。Electron 运行时通过官方地址下载并校验 SHA-256。

```powershell
npm.cmd ci
npm.cmd run build:native
npm.cmd run check
npm.cmd test
npm.cmd start
```

如需重新生成托盘图标：

```powershell
npm.cmd run build:tray
```

首次编译的辅助程序可能受到本机应用控制策略限制；请按系统提示和组织策略处理。

## 构建 Android 端

使用 JDK 21、Gradle 8.13 和 Android SDK 36。在 `android/local.properties` 中填写本机 SDK 路径，该文件不进入版本控制。

```properties
sdk.dir=C\:/Android/Sdk
```

构建可安装的开发包并运行测试：

```powershell
gradle -p android assembleDebug testDebugUnitTest -PpublicBuild=true
```

构建不注入个人模型配置的发行包：

```powershell
gradle -p android assembleRelease
```

发行包输出到 `android/app/build/outputs/apk/release/`，需使用自己的 Android 签名密钥签名后分发。公开开发包必须添加 `-PpublicBuild=true`，以禁用本机个人配置注入。个人本地构建可以读取 `.private/` 配置，但该目录、签名密钥和个人预配置 APK 均不随仓库上传。见 [发布说明](docs/PUBLISHING.md)。

## 项目结构

```text
src/       Electron 主进程、配对与截图传输
ui/        Windows 控制台界面
native/    Windows 原生截图、Ctrl 手势及长图拼接
android/   Android 原生客户端
assets/    图标与矢量源文件
scripts/   检查、环境准备及打包
test/      桌面与原生测试
```

本仓库提供独立实现的项目源码，不包含第三方原软件、分析解包文件、个人模型密钥、设备配对凭据、签名密钥或笔试记录。

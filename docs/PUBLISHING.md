# 发布与私密配置

Git 仓库仅包含可构建源码。个人配置、构建缓存、签名密钥、安装包及运行时连接状态由 `.gitignore` 排除；历史分析笔记和截图保留在本机，不随首次公开上传提交。

## 源码

首次提交前检查待提交文件，确认没有 `.private/`、`.env`、`android/local.properties`、`BuildConfig.java`、APK、EXE、签名密钥、证书私钥、设备信任文件或个人记录。

## Windows 安装包

官方 Electron 运行时、原生截图辅助程序和界面组成完整目录；分发时使用整个便携 ZIP。目录不能只保留 EXE。电脑端不需要也不应包含模型 API Key。

## Android 安装包

本机已有个人预配置开发包，其中可能包含模型密钥，不得直接上传到公开仓库或 Releases。通用发行构建使用 `assembleRelease`，该构建将默认 API Key、服务地址、模型及个人配置标识设为空值，再使用自己的 Android 签名密钥签名。

如需使用开发签名生成可安装的公开测试包，运行 `gradle -p android assembleDebug -PpublicBuild=true`。该属性禁止读取本机个人密钥并清空默认模型配置；构建后仍需验证生成配置及 APK 中没有个人密钥。开发签名包仅用于测试。

不要上传签名私钥、密钥口令或个人预配置 APK。若已安装旧版，覆盖更新需要保持相同包名与签名；重新签名的通用包不能直接覆盖不同签名的旧包。

公开测试包单独放入本机 `release/public/`，不覆盖 `release/` 中的个人版 APK。上传 Releases 时明确选择已检查的公开包，不使用包含个人包的通配符。

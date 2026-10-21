# BreezeScreen API21

> **版本：v1.0.0 · Android 5.0（API 21）独立兼容版**

`BreezeScreen` 是一个面向 Android TV、电视盒子和横屏设备的短视频网页浏览器。它借助系统 WebView 打开官方网页，并提供适合遥控器的方向滚动、确认播放/暂停、菜单控制栏、网页全屏、刷新与本机会话清除功能。

## 来源与版本定位

本仓库的 **BreezeScreen API21 v1.0.0** 参照 [`mytv-android/myDV`](https://github.com/mytv-android/myDV) **V1.2.22 稳定版**公开呈现的电视端产品功能、遥控器交互和 Release 说明而独立编写。myDV V1.2.22 的公开 APK 标示最低 Android API 23；本项目从零实现为 **Android 5.0 / API 21** 可构建版本。

该参照关系不表示代码移植或官方关联。myDV 的公开仓库未提供 Android 源码，因此本项目不复制、移植或反向工程其未公开实现。完整范围见 [`docs/VERSION_ORIGIN.md`](docs/VERSION_ORIGIN.md)。

| 项目 | BreezeScreen API21 v1.0.0 |
|---|---|
| 应用名称 | **BreezeScreen** |
| 包名 | `app.breezescreen.tv` |
| 仓库 | `breezescreen-api21` |
| 参照版本 | myDV **V1.2.22**（公开功能与 Release 说明） |
| 最低系统 | **Android 5.0 / API 21** |
| 技术实现 | Java + Android View XML + 系统 WebView |
| 内容路径 | 只加载官方网页；不内置私人接口或视频资源 |

## 遥控器操作

| 按键 | 行为 |
|---|---|
| 上 / 下 | 按页滚动网页内容 |
| 左 / 右 | 小幅横向滚动 |
| 确认 / 播放暂停 | 播放或暂停当前可见 HTML5 视频 |
| 菜单 / 设置 | 显示或隐藏控制栏 |
| 返回 | 退出网页全屏；首次打开控制栏；再次确认退出应用 |

## 登录与隐私

登录由官方网页的正常流程处理。BreezeScreen 不读取、显示、上传或自动填写 Cookie、账号或密码。系统 WebView 可能在本机保存网页会话；你可通过应用控制栏的 **清除会话** 删除本机 Cookie、缓存和历史记录。

本项目不实现私有 API 签名、设备指纹伪装、验证码绕过、账号抓取、DRM 规避或视频流抓取。请遵守相关平台条款和当地法律。

## 构建与发布

需要 JDK 17 与 Android SDK Platform 35：

```bash
./gradlew test assembleDebug
```

GitHub Actions 会从仓库 Secrets 恢复固定发布密钥，并在推送 `incoming/*.zip` 或手动运行工作流时构建签名 Release APK。新仓库使用 `BREEZESCREEN_*` Secrets；详见 [`docs/RELEASE_AUTOMATION.md`](docs/RELEASE_AUTOMATION.md)。首个发布标签为 **v1.0.0**。

## 许可证与致谢

本仓库中新写代码以 MIT License 发布。`myDV`、`抖音`及其相关商标归各自权利人所有；BreezeScreen 不隶属于或获其背书。

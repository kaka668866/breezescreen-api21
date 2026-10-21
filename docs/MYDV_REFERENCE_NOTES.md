# myDV 正式参照核对记录

> **正式参照仓库**：[`mytv-android/myDV`](https://github.com/mytv-android/myDV)，而非组织中的 `mytv-android/mytv-android` IPTV 项目。

## 公开仓库与 Release 事实

| 项目 | 观测结果 |
|---|---|
| 产品名称 | `my Debug Video TV` / **myDV** |
| 公开发布版本 | `V1.2.22`，2026-08-13 发布 |
| Release 资产 | `app-release.apk`，15,569,605 bytes |
| 包名 | `com.github.mytv.dv` |
| 版本号 | `versionName 1.2.22`，`versionCode 22` |
| 原版最低系统 | `minSdk 23`（Android 6.0） |
| 目标要求 | 独立兼容版本必须降至 `minSdk 21`（Android 5.0） |
| 公开源码 | 仓库仅公开 README、远程配置、图片和发布 APK；未提供 Android Gradle 源码工程 |

## 公开描述的产品功能

README 与 Release V1.2.22 描述的能力包括：短视频推荐浏览、直播观看、弹幕、视频搜索、遥控器焦点移动、左右快进/快退、确认播放/暂停、导航栏、进度条、播放重试、独立直播清晰度和多账户隔离。登录说明要求用户自行填写网页端 Cookie。

## API 21 独立实现边界

由于公开仓库没有提供原 Android 源码，API 21 版本应被定义为**独立重写的兼容客户端**，而非复制未公开实现。目标是复刻公开可见的 TV 页面信息架构和遥控器交互，不复制或反向工程发布 APK 中的私有网络签名、设备伪装、验证码自动化、受保护接口调用或其他规避平台机制。

用户可在本地应用内自行配置会话信息；项目不采集、上传或代填任何 Cookie、账户凭据或私密标识符。

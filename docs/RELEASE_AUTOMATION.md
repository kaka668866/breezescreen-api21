# BreezeScreen API21 自动签名与发布说明

本仓库的发布工作流使用 **同一把 Android 发布密钥** 对每个 Release APK 签名。因此，只要设备上已安装的应用也来自这个仓库的发布链，且新的 `versionCode` 更高，Android 就允许覆盖安装。任何使用不同证书签名的旧 APK 都不能被覆盖安装，这是 Android 的系统安全规则，而不是工作流可以绕过的限制。

## 必须一次性配置的仓库机密

出于安全考虑，公有仓库绝不能提交 `.jks` 文件或密码。请在 GitHub 仓库的 **Settings → Secrets and variables → Actions** 中添加下表中的四个 Secrets。首个发布密钥已经由维护流程生成；在拥有 Secrets 写入权限的环境中，将对应值保存到这里即可。后续不得更换这些值，否则新版本无法覆盖已签名版本。

| Secret 名称 | 内容 |
|---|---|
| `BREEZESCREEN_KEYSTORE_BASE64` | 发布 `.jks` 文件的单行 Base64 编码 |
| `BREEZESCREEN_STORE_PASSWORD` | 密钥库密码 |
| `BREEZESCREEN_KEY_ALIAS` | 发布别名 |
| `BREEZESCREEN_KEY_PASSWORD` | 别名私钥密码 |

## 从源码 ZIP 发布

将此仓库结构的源码 ZIP 上传或提交到 `incoming/` 目录后推送至 `main`，工作流会自动选择最新 ZIP，解压并寻找 `settings.gradle` 或 `settings.gradle.kts`，构建 Release APK 并创建 GitHub Release。也可以在 **Actions → Build signed APK and publish release → Run workflow** 中手动填写 ZIP 相对路径与版本标签。

ZIP 必须包含完整的 Gradle Android 项目、`gradlew` 和本仓库中 `app/build.gradle` 的签名/版本参数支持。工作流对自动版本使用 `10000 + GitHub Run Number` 作为 `versionCode`，确保其高于本地 v1.0.0 的版本号 1。

首个发布为 **BreezeScreen API21 v1.0.0**。它是参照 `mytv-android/myDV` **V1.2.22** 的公开功能与遥控器交互独立编写的 Android 5.0 兼容版本；其包名、应用名称和发布密钥链都从本仓库开始独立维护。

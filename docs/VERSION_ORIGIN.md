# BreezeScreen API21 v1.0.0 版本来源说明

## 本版本是什么

**BreezeScreen API21 v1.0.0** 是一个独立维护的 Android TV/横屏短视频网页客户端，应用包名为 `app.breezescreen.tv`，最低支持 **Android 5.0（API 21）**。

## 参照来源

本版本以 [`mytv-android/myDV`](https://github.com/mytv-android/myDV) 的公开产品描述、遥控器交互说明及其 **V1.2.22 稳定版**的公开 Release 变更说明作为功能参照。myDV V1.2.22 的公开 APK 元数据标示最低 Android API 23；本项目的目标是将其对外可见的电视端交互理念以更低系统版本重新实现。

## 独立改写范围

本版本使用 Java、Android View XML 和系统 WebView 重新编写。它不包含或移植 myDV 未公开的 Android 源码，也不反向工程、调用或模拟私有网络签名、设备伪装、验证码处理、Cookie 自动填充、账号采集、DRM 规避或视频流抓取逻辑。

| 项目 | BreezeScreen API21 v1.0.0 |
|---|---|
| 应用名称 | BreezeScreen |
| 仓库标识 | `breezescreen-api21` |
| 参照版本 | myDV V1.2.22（公开说明与 Release） |
| 最低系统 | Android 5.0 / API 21 |
| 登录 | 官方网页自身处理；本应用不读取或上传 Cookie/账号/密码 |
| 发布链 | 从 BreezeScreen API21 v1.0.0 起独立维护 |

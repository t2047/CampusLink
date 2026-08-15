# CampusLink Android Core Chat

> 组员接手开发前，请先阅读[移动端开发状态与后续路线](DEVELOPMENT_STATUS_cn.md)。

原生 Kotlin / Jetpack Compose 客户端，首版聚焦统一 Core Chat。它直接连接 Spring Boot 的
`/api/chat/stream` 和 `/api/chat/resume`，展示 SSE 流式文本、Agent/Utility 执行步骤、
Lost & Found 匹配卡片及 HITL 确认。

## 构建变体

| 变体 | API 地址 | 用途 |
|---|---|---|
| `localDebug` | `http://10.0.2.2:8080/` | Android 模拟器连接本机后端 |
| `demoDebug` | `https://campuslink.tokeninf.xyz/` | 组员安装和云端演示 |
| `prodRelease` | `https://campuslink.tokeninf.xyz/` | 后续正式签名构建 |

只有 `localDebug` 允许访问指定的明文地址；Demo 与生产版本不会信任自签名证书，也不会关闭 TLS 校验。

## 本地构建

需要 Android Studio、Android SDK 36 和 JDK 17（也可使用 Android Studio 自带 JBR）。

```bash
cd frontend_mobile
./gradlew testDemoDebugUnitTest lintDemoDebug detekt
./gradlew assembleDemoDebug
```

APK 位于 `app/build/outputs/apk/demo/debug/app-demo-debug.apk`。GitHub Actions 会将它重命名为
`CampusLink-core-chat-demo.apk` 并保留 14 天。

正式版本不会使用 Debug 签名。构建 `prodRelease` 前需通过本机或 CI Secret 提供：

```bash
export CAMPUSLINK_RELEASE_STORE_FILE=/secure/path/campuslink-release.jks
export CAMPUSLINK_RELEASE_STORE_PASSWORD='***'
export CAMPUSLINK_RELEASE_KEY_ALIAS=campuslink
export CAMPUSLINK_RELEASE_KEY_PASSWORD='***'
./gradlew assembleProdRelease
```

签名文件和密码不得提交到 Git；缺少上述配置时 `prodRelease` 会主动失败。

## 安全与数据

- JWT 经 Android Keystore 加密后保存在应用私有目录。
- Room 使用 SQLCipher；数据库密钥由 Keystore 包装。
- 不记录 Token、密码、完整请求头或 Agent 敏感结果。
- 聊天历史只保存在当前设备，不会跨设备同步。
- 退出登录后不同账号的历史相互隔离；设置页可清除当前账号的本地历史。

## 当前边界

- Chat Core 只接受文字，因此暂不支持图片、文件和语音消息。
- Lost & Found、Facilities 和 Mail 通过编排层调用，暂不提供各模块的完整原生页面。
- Mail 是否可用取决于云端 Gmail OAuth 配置。
- 正式签名配置入口已提供；签名文件生成、保管和 Play Store 发布不在首版范围内。

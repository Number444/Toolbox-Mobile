# Toolbox-Mobile

Windows 桌面应用 [Toolbox](https://github.com/Number444) 的 Android 伴侣，Kotlin + Jetpack Compose 构建。

## 功能

- **远程连接**：扫码绑定（CameraX + ML Kit），与桌面端配对
- **工具**：内置 DSH 远程工具（WebView 全屏，edge-to-edge + 毛玻璃底栏）
- **设置**：连接与偏好管理

## 技术栈

- Kotlin / Jetpack Compose（Material3，BOM 2024.12.01）
- [haze](https://github.com/chrisbanes/haze) 毛玻璃底栏
- CameraX + ML Kit 扫码
- androidx.webkit

## 构建

```powershell
.\gradlew assembleDebug        # 调试包
.\gradlew assembleRelease      # 正式包（需签名配置，见下）
```

release 构建后 APK 自动导出到 `C:\Agent Space\ToolboxMobile-Releases\`。

### 签名

`toolbox.keystore` 不入库，密码通过用户级 `~/.gradle/gradle.properties` 提供：

```properties
TOOLBOX_STORE_PASSWORD=***
TOOLBOX_KEY_PASSWORD=***
```

详细架构与发布规范见 `docs/设计方案-整体框架.md`。

# GitHub 云端打包

流水线：`.github/workflows/android-apk.yml`。

## 触发方式

- 向 `main` 推送代码：自动打包 Debug APK。
- 提交目标分支为 `main` 的 Pull Request：打包 Debug APK。
- 仓库 Actions → Android APK → Run workflow：手动选择 `debug` 或 `release`。

## 获取 APK

进入对应工作流运行页面，在 Artifacts 下载 `SyncGameSave-类型-运行编号` 压缩包，解压后获取 APK。

- `debug`：可安装调试包。每次干净 Runner 可能生成不同的调试签名，跨次安装出现签名冲突时不能直接覆盖；正式持续分发应另行配置固定签名。
- `release`：当前工程没有发布签名配置，因此生成未签名 APK，需要自行签名后安装或分发。
- 构建产物默认保存 14 天，实际保留时长受仓库／组织策略影响。

## 构建环境与范围

- Ubuntu 24.04 GitHub 托管 Runner。
- JDK 17、Gradle 8.2、Android SDK Platform 34、Build Tools 34.0.0。
- 所有自行编写的工作流 `run` 步骤使用 PowerShell 7（`pwsh`）。
- 只执行 APK assemble，显式排除 `test` 和 `lint`；不运行应用、模拟器或设备测试。
- Gradle 由 `gradle/actions/setup-gradle` 安装，直接调用 `gradle`，不依赖初始工程缺失的 `gradle-wrapper.jar`，也不需要 Linux `gradlew`。
- 只需仓库默认 `GITHUB_TOKEN` 的代码读取权限，不需要把本机 SSH 私钥上传到 Actions Secrets。
- 七牛凭证仍在安装后的应用内填写，未写入流水线或构建产物。

此次配置不会在本机执行项目构建。工作流推送到 GitHub 后由 GitHub Runner 执行打包；产物是否构建成功以 Actions 的实际结果为准。

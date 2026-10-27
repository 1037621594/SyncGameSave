# GitHub 云端打包

流水线：`.github/workflows/android-apk.yml`。

## 触发方式

- 向 `main` 推送代码：自动打包 Debug APK。
- 提交目标分支为 `main` 的 Pull Request：打包 Debug APK。
- 仓库 Actions → Android APK → Run workflow：手动选择 `debug` 或 `release`。

## 获取 APK

进入对应工作流运行页面，在 Artifacts 下载 `SyncGameSave-类型-运行编号` 压缩包，解压后获取 APK。

- `debug`：可安装调试包，使用仓库内固定开发证书签名。
- `release`：可安装发布构建，使用与 Debug 相同的固定开发证书，非生产发布密钥。
- 构建产物默认保存 14 天，实际保留时长受仓库／组织策略影响。

## 构建环境与范围

- Ubuntu 24.04 GitHub 托管 Runner。
- JDK 17、Gradle 8.2、Android SDK Platform 34、Build Tools 34.0.0。
- Android SDK 初始化显式指定 `packages: platform-tools`，覆盖 setup-android v3 默认的旧 `tools` 包；Platform 34 和 Build Tools 34.0.0 由后续步骤安装，避免 `Failed to find package 'tools'`。
- 所有自行编写的工作流 `run` 步骤使用 PowerShell 7（`pwsh`）。
- 只执行 APK assemble，显式排除 `test` 和 `lint`；不运行应用、模拟器或设备测试。
- Gradle 由 `gradle/actions/setup-gradle` 安装，直接调用 `gradle`，不依赖初始工程缺失的 `gradle-wrapper.jar`，也不需要 Linux `gradlew`。
- 只需仓库默认 `GITHUB_TOKEN` 的代码读取权限，不需要把本机 SSH 私钥上传到 Actions Secrets。
- 七牛凭证仍在安装后的应用内填写，未写入流水线或构建产物。

此次配置不会在本机执行项目构建。工作流推送到 GitHub 后由 GitHub Runner 执行打包；产物是否构建成功以 Actions 的实际结果为准。


## 固定开发签名

- Debug 和 Release 都通过 Gradle 的 `signingConfigs.development` 使用 `signing/development.keystore`，不再使用 Runner 临时生成的调试证书。
- 这是新建的项目专用、公开的开发身份，不是 SSH 私钥或现有的生产签名；用于个人安装与开发版本持续更新，不作为可信的正式发行身份。
- keystore 格式为 JKS，别名 `syncgamesave-development`，存储和密钥口令均为 `android`。该证书随仓库保存，不依赖 GitHub 缓存或额外 Secrets。
- 不要删除或重新生成该文件，否则会再次改变 APK 签名。生产发布应另建私有证书并通过 GitHub Secrets 注入。
- 旧包由 Runner 随机密钥签名，本项目没有保留该密钥，不能通过新证书直接覆盖它。首次切换固定证书通常需要卸载旧包；卸载会清除本地应用配置，请先记录配置并确认存档已保存。后续固定签名包可继续覆盖安装。

# 固定开发签名

`development.keystore` 是本项目新建的公开开发签名，供 GitHub Actions 的 Debug 和 Release 构建复用，以保持 APK 签名稳定。

- 格式：JKS
- 别名：`syncgamesave-development`
- keystore 口令：`android`
- key 口令：`android`

这是用于个人安装和开发的公开身份，不是生产发布证书，不包含用户的 SSH 私钥。不要重新生成或替换此文件，否则已安装的固定签名包将无法直接升级。

正式发行应使用单独的私有签名，通过 GitHub Secrets 注入，不能把正式密钥提交到仓库。

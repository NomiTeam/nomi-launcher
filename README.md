# Nomi Launcher

基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的 Nomi Minecraft 启动器。账号注册和登录均使用指定 QQ 群内的一次性验证码，不需要密码。

## 使用

- 打开 Nomi 群验证码登录窗口，完整 `登录 <验证码>` 命令自动复制，在指定群粘贴发送。
- 已有账号直接登录；没有账号时按机器人的提示发送 `注册 <id>`，成功后窗口自动关闭。ID 为 3 至 16 位字母、数字或下划线。
- 窗口只有右下角关闭按钮，关闭会取消未完成的申请。名称冲突会结束申请，请关闭后重新获取验证码。
- 点击账户卡片第一个改名按钮直接修改游戏名；也可在群里发送 `改名 <新id>`，然后重新登录。默认冷却 7 天，角色 UUID 保持不变。
- 使用 HMCL 原有账号卡片的皮肤按钮上传皮肤，手臂模型自动判断。Nomi 账号全部便携存储，不加载或写入原 HMCL 全局账户。
- 当前测试群可直接发送命令，无需艾特。

需要配套的 [Nomi Auth](https://github.com/NomiTeam/nomi-auth) 与 [QQ 插件](https://github.com/NomiTeam/nomi-auth-plugin)。管理会话到期后重新通过群验证码登录；游戏会话由启动器保存并刷新。

## 本地构建

使用 JDK 17 或更高版本：

```text
gradlew.bat :HMCL:test --tests *NomiServiceTest :HMCL:shadowJar
java -Dnomi.api.base=http://127.0.0.1:8000 -jar HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar
```

Linux 使用 `./gradlew`。以 `HMCL/build/libs/` 的实际文件名为准。默认地址是本机开发服务；公开部署前设置真实 HTTPS 服务地址。构建不包含 HMCL 官方签名。

新实例以及原先未指定进服目标的实例默认连接 `play.missdrop.cn`；已有其他服务器或单人存档目标的实例保持其设置。首页不再显示上游预览版提示，窗口标题使用 Nomi Launcher。

## 上游同步

本仓库是 HMCL 的 fork，`nomi` 分支保存定制改动。上游基线见 `UPSTREAM_COMMIT.txt`。保留 `upstream` remote 后，通过 fetch 和 merge 在本地合并上游更新并运行验证，避免直接覆盖定制文件。

## 许可证

沿用 HMCL 的 GPLv3 及附加条款，见 [LICENSE](LICENSE)。发布修改版需保留版权声明、提供对应源码，并使用可区分的名称或版本；本项目名称为 Nomi Launcher。

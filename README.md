# Nomi Launcher

基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的 Nomi Minecraft 启动器。账号注册和登录均使用指定 QQ 群内的一次性验证码，不需要密码。

## 使用

- 新用户输入用户名，点击创建账号，在指定群发送启动器显示的注册命令。
- 已有账号或重新安装后，打开登录窗口直接领取验证码，在群内发送已自动复制的登录命令。机器人按发信成员查找账号；没有账号时提示先注册。
- 登录后通过 HMCL 原有账号卡片的皮肤按钮上传或更换皮肤，手臂模型自动判断；账号窗口可修改游戏名，角色 UUID 保持不变。所有 Nomi 账号使用便携存储，不加载或写入原 HMCL 全局账户。
- 当前测试群支持直接发送命令，不必艾特；只开放艾特事件的群需要艾特机器人。

需要配套的 [Nomi Auth](https://github.com/NomiTeam/nomi-auth) 与 [QQ 插件](https://github.com/NomiTeam/nomi-auth-plugin)。管理会话到期后重新通过群验证码登录；游戏会话由启动器保存并刷新。

## 本地构建

使用 JDK 17 或更高版本：

```text
gradlew.bat :HMCL:test --tests *NomiServiceTest :HMCL:shadowJar
java -Dnomi.api.base=http://127.0.0.1:8000 -jar HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar
```

Linux 使用 `./gradlew`。以 `HMCL/build/libs/` 的实际文件名为准。默认地址是本机开发服务；公开部署前设置真实 HTTPS 服务地址。构建不包含 HMCL 官方签名。

## 上游同步

本仓库是 HMCL 的 fork，`nomi` 分支保存定制改动。上游基线见 `UPSTREAM_COMMIT.txt`。保留 `upstream` remote 后，通过 fetch 和 merge 在本地合并上游更新并运行验证，避免直接覆盖定制文件。

## 许可证

沿用 HMCL 的 GPLv3 及附加条款，见 [LICENSE](LICENSE)。发布修改版需保留版权声明、提供对应源码，并使用可区分的名称或版本；本项目名称为 Nomi Launcher。

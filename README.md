# Nomi Launcher

基于 [HMCL](https://github.com/HMCL-dev/HMCL) 的定制启动器，配合 [Nomi Auth](https://github.com/NomiTeam/nomi-auth) 和 [QQ 插件](https://github.com/NomiTeam/nomi-auth-plugin) 使用群验证码登录，无需密码。

## 使用

- 打开登录窗口，在指定群发送自动复制的 `登录 <验证码>`；未注册时按提示发送 `注册 <id>`。
- ID 为 3 至 16 位字母、数字或下划线。成功后窗口自动关闭，手动关闭会取消申请。
- 账户卡片提供改名与原生皮肤上传按钮，皮肤模型自动判断。也可在群内发送 `改名 <新id>`，启动前自动同步当前 ID。
- 改名默认冷却 7 天，UUID 不变；账户仅使用便携存储，不加载全局账户。

认证地址通过构建参数 `-PnomiApiBase` 或环境变量 `NOMI_API_BASE` 配置；未配置时使用本机开发地址。运行时可用 `-Dnomi.api.base=https://auth.example.com` 覆盖。

登录窗口提供到期倒计时和失败重试；登录凭据保存失败时会提示处理存储问题。

游戏令牌默认 24 小时，启动器使用私密刷新令牌自动续期；连续 30 天未续期需重新群验证。旧版账户升级后先重新群验证一次。刷新令牌不传给游戏。

## 构建与配置

需要 JDK 17 或更高版本。Windows 执行：

```text
gradlew.bat :HMCL:makeExecutables -PnomiApiBase=https://auth.example.com -PnomiDefaultServer=play.example.com
java -jar HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar
```

Linux/macOS 使用 `./gradlew`。该任务生成 JAR、Windows EXE 和启动脚本，产物位于 `HMCL/build/libs/`；Windows 可直接运行 EXE，文件名以实际构建结果为准。

自动进服地址由 `-PnomiDefaultServer` 或环境变量 `NOMI_DEFAULT_SERVER` 提供，不写死在源码中；不配置就不默认进服。实例明确关闭自动加入、设置其他服务器或单人存档时，尊重该设置。

运行时可用 `-Dnomi.defaultServer=地址` 覆盖默认进服目标，传空值关闭；本机调试可用 `-Dnomi.api.base=http://127.0.0.1:8000` 覆盖认证地址。这些参数放在 `-jar` 前。

当前使用手动更新，设置页可打开 Nomi 发布页，不检查 HMCL 官方更新。

## 上游与许可证

定制分支为 `nomi`，上游基线见 `UPSTREAM_COMMIT.txt`。沿用 HMCL 的 [许可证及附加条款](LICENSE)，发布时保留版权声明并提供对应源码；本构建不包含 HMCL 官方签名。

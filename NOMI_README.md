# Nomi Launcher

基于 HMCL 的 Nomi 启动器开发版。保留 HMCL 许可证与版权，产品名称已区分修改版本。

## 构建和运行

构建使用 JDK 17 或更高版本：

```text
gradlew.bat :HMCL:test --tests *NomiServiceTest :HMCL:shadowJar
java -Dnomi.api.base=http://127.0.0.1:8000 -jar HMCL/build/libs/HMCL-3.17.SNAPSHOT.jar
```

Linux 使用 `./gradlew`。输出 JAR 名称随版本号改变，以 `HMCL/build/libs/` 中的实际文件为准。默认认证地址为本机开发服务。公开发布前必须配置实际 HTTPS 服务地址；当前未绑定域名。

账号页提供 Nomi 登录、创建、找回、皮肤上传、正常或纤细手臂、默认皮肤恢复、游戏名与密码修改。皮肤显示的是 PNG 纹理预览。注册只需指定群内一次验证码，用户名与成员身份由后端唯一约束保护。

固定登录名不会随着游戏名改变。修改游戏名后需要关闭游戏、重新登录并重新启动。管理令牌不持久化，到期重新登录。游戏令牌沿用 HMCL 的账户存储和刷新。

启动前检查当前账号认证根地址，并取消离线跳过认证的回退。现有配置中的其他账号不会被删除，但不能通过 Nomi 启动流程使用。普通外置认证启动器仍可使用有效 Nomi 账号进服；限制客户端来源不属于本版功能。

上游基线：`upstream/main`，导入时 SHA 记录在 `UPSTREAM_COMMIT.txt`。真实 QQ 和 Minecraft 服务端尚需部署联调。构建不包含 HMCL 官方签名，不能声称是 HMCL 官方版本。

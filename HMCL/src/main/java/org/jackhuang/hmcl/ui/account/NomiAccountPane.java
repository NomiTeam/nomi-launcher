/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2021  huangyuhui <huanghongxun2008@126.com> and contributors
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
package org.jackhuang.hmcl.ui.account;

import com.google.gson.JsonObject;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Nomi login, single-code registration and account management dialog.
@NotNullByDefault
public final class NomiAccountPane extends JFXDialogLayout implements DialogAware {
    /// Fixed login name, which does not change with the game name.
    private final TextField username = new TextField();
    /// Current password, retained only in the form while the dialog is open.
    private final PasswordField password = new PasswordField();
    /// New password or repeated registration password.
    private final PasswordField repeated = new PasswordField();
    /// Business and progress messages.
    private final Label status = new Label();
    /// Operations and registration progress.
    private final VBox body = new VBox(10);
    /// Polling schedule; created only for a live registration or recovery.
    private final Timeline polling = new Timeline();
    /// Management bearer token, never persisted on disk.
    private @Nullable String managementToken;
    /// Current application private polling credential.
    private @Nullable String pollToken;
    /// Current application ID.
    private @Nullable String requestId;
    /// Whether current polling is a recovery rather than a registration.
    private boolean recovering;
    /// Prevents concurrent form operations.
    private boolean busy;
    /// Prevents asynchronous callbacks from updating a closed dialog.
    private boolean closed;

    /// Creates the dialog with Nomi-specific actions and no other authentication methods.
    public NomiAccountPane() {
        setHeading(new Label("Nomi 账号"));
        username.setPromptText("固定登录名（首次与游戏名相同）");
        password.setPromptText("密码，至少 10 位");
        repeated.setPromptText("注册时重复密码；找回时填写新密码");
        status.setWrapText(true);
        var login = button("登录", this::login);
        var register = button("创建账号", this::register);
        var recover = button("找回密码", this::recover);
        body.getChildren().setAll(username, password, repeated, login, register, recover, status);
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(520);
        setBody(scroll);
        var close = button("关闭", () -> fireEvent(new DialogCloseEvent()));
        setActions(close);
        setPrefWidth(560);
    }

    /// Creates a standard button running on the JavaFX thread.
    private Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Performs network work off-thread, serializes actions, and ignores closed-dialog callbacks.
    private <T> void run(Callable<T> action, Consumer<T> success) {
        if (busy || closed) return;
        busy = true;
        body.setDisable(true);
        CompletableFuture.supplyAsync(() -> {
            try { return action.call(); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            busy = false;
            if (closed) return;
            body.setDisable(false);
            if (failure != null) {
                @Nullable Throwable cause = failure.getCause();
                status.setText(cause == null ? "请求失败" : cause.getMessage());
            } else success.accept(result);
        }));
    }

    /// Builds a JSON object from text key/value pairs.
    private JsonObject json(String... pairs) {
        var json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) json.addProperty(pairs[i], pairs[i + 1]);
        return json;
    }

    /// Logs in through both the management API and HMCL's external-account factory.
    private void login() {
        String name = username.getText().trim();
        String pass = password.getText();
        run(() -> {
            var session = NomiService.request("POST", "/api/v1/sessions", json("username", name, "password", pass), null);
            var server = AuthlibInjectorServer.locateServer(NomiService.AUTH);
            if (!server.getUrl().replaceAll("/+$", "").equals(NomiService.AUTH.replaceAll("/+$", "")))
                throw new java.io.IOException("认证地址与 Nomi 配置不一致");
            var account = Accounts.getAccountFactoryByAuthlibInjectorServer(server)
                    .create(new CreateAccountPane.DialogCharacterSelector(), name, pass, null, null);
            return new LoginResult(account, session.get("access_token").getAsString());
        }, result -> {
            if (Accounts.isAccountFilesReadOnly(result.account())) {
                status.setText("账号文件只读，请先在设置中处理存储权限。");
                return;
            }
            int index = Accounts.getAccounts().indexOf(result.account());
            if (index >= 0) Accounts.getAccounts().set(index, result.account());
            else Accounts.getAccounts().add(result.account());
            Accounts.setSelectedAccount(result.account());
            managementToken = result.token();
            password.clear();
            repeated.clear();
            showManagement();
        });
    }

    /// Values returned by a completed login operation.
    @NotNullByDefault
    private record LoginResult(Account account, String token) { }

    /// Begins one-time group registration after local password comparison.
    private void register() {
        if (!password.getText().equals(repeated.getText())) {
            status.setText("两次密码不一致");
            return;
        }
        String name = username.getText().trim();
        String pass = password.getText();
        run(() -> NomiService.request("POST", "/api/v1/registrations", json("name", name, "password", pass), null),
                result -> startPolling(result, false));
    }

    /// Starts recovery; only the account's bound group member may verify it.
    private void recover() {
        String name = username.getText().trim();
        run(() -> NomiService.request("POST", "/api/v1/recoveries", json("username", name), null),
                result -> startPolling(result, true));
    }

    /// Displays the public challenge and starts non-overlapping private status requests.
    private void startPolling(JsonObject result, boolean recovery) {
        requestId = result.get("request_id").getAsString();
        pollToken = result.get("poll_token").getAsString();
        recovering = recovery;
        String group = result.has("group_label") ? result.get("group_label").getAsString() : "服务器指定群";
        var command = new TextField("@机器人 " + (recovery ? "找回 " : "注册 ") + result.get("code").getAsString());
        command.setEditable(false);
        body.getChildren().setAll(new Label("请在" + group + "发送下方命令（可选中复制，5 分钟内有效）"), command, status);
        if (recovery) {
            body.getChildren().add(repeated);
            body.getChildren().add(button("设置新密码", this::completeRecovery));
        } else body.getChildren().add(button("取消申请", this::cancel));
        status.setText("等待群验证，验证码只使用一次。");
        polling.getKeyFrames().setAll(new KeyFrame(Duration.seconds(2), event -> poll()));
        polling.setCycleCount(Timeline.INDEFINITE);
        polling.play();
    }

    /// Queries private application status; a registration result can then log in automatically.
    private void poll() {
        if (requestId == null || pollToken == null) return;
        String path = "/api/v1/" + (recovering ? "recoveries/" : "registrations/") + requestId;
        String token = pollToken;
        run(() -> NomiService.request("GET", path, null, token), result -> {
            String state = result.get("status").getAsString();
            if (!"PENDING".equals(state)) {
                polling.stop();
                status.setText("状态：" + state);
                if ("COMPLETED".equals(state) && !recovering) {
                    resetForm();
                    login();
                }
                else if (!"VERIFIED".equals(state)) resetForm();
            }
        });
    }

    /// Cancels a pending registration atomically before restoring the initial form.
    private void cancel() {
        if (requestId == null || pollToken == null) return;
        String path = "/api/v1/registrations/" + requestId;
        String token = pollToken;
        run(() -> NomiService.request("DELETE", path, null, token), result -> {
            polling.stop();
            resetForm();
            password.clear();
            repeated.clear();
            status.setText("申请已取消");
        });
    }

    /// Consumes a verified recovery authorization and requires a new login afterward.
    private void completeRecovery() {
        if (requestId == null || pollToken == null) return;
        String path = "/api/v1/recoveries/" + requestId + "/complete";
        String token = pollToken;
        String pass = repeated.getText();
        run(() -> NomiService.request("POST", path, json("password", pass), token), result -> {
            polling.stop();
            password.clear();
            repeated.clear();
            resetForm();
            status.setText("密码已重置，请重新登录。");
        });
    }

    /// Restores login fields without losing the displayed outcome.
    private void resetForm() {
        requestId = null;
        pollToken = null;
        body.getChildren().setAll(username, password, repeated, button("登录", this::login),
                button("创建账号", this::register), button("找回密码", this::recover), status);
    }

    /// Shows UUID-safe game-name and PNG-skin actions for a live management session.
    private void showManagement() {
        String token = managementToken == null ? "" : managementToken;
        run(() -> NomiService.request("GET", "/api/v1/me", null, token), result -> {
            var profile = result.getAsJsonObject("profile");
            var gameName = new TextField(profile.get("name").getAsString());
            var confirmPassword = new PasswordField();
            confirmPassword.setPromptText("改名或改密码时输入当前密码");
            var newPassword = new PasswordField();
            newPassword.setPromptText("新密码，至少 10 位");
            var model = new ComboBox<String>();
            model.getItems().setAll("default", "slim");
            model.setValue(profile.get("skin_model").getAsString());
            var preview = new ImageView();
            preview.setFitWidth(128);
            preview.setPreserveRatio(true);
            preview.setSmooth(false);
            if (!profile.get("skin_hash").isJsonNull())
                preview.setImage(new Image(NomiService.BASE + "/textures/" + profile.get("skin_hash").getAsString(), true));
            body.getChildren().setAll(new Label("登录名：" + result.get("login_name").getAsString()),
                    new Label("UUID：" + profile.get("uuid").getAsString()), gameName, confirmPassword,
                    button("修改游戏用户名", () -> {
                        var payload = json("name", gameName.getText().trim(), "password", confirmPassword.getText());
                        run(() -> NomiService.request("PATCH", "/api/v1/me/profile/name", payload, token), updated -> {
                            confirmPassword.clear();
                            status.setText("改名成功，请关闭游戏并重新登录，UUID 保持不变。");
                        });
                    }), new Label("当前皮肤纹理"), preview, model, button("上传或更换皮肤", () -> {
                        var chooser = new FileChooser();
                        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 皮肤", "*.png"));
                        @Nullable File file = chooser.showOpenDialog(getScene().getWindow());
                        if (file != null) {
                            String selectedModel = model.getValue();
                            run(() -> NomiService.upload(file.toPath(), selectedModel, token), updated -> {
                                preview.setImage(new Image(updated.get("url").getAsString(), true));
                                status.setText("皮肤已更新，请重新进服。");
                            });
                        }
                    }), button("恢复默认皮肤", () -> run(() -> NomiService.request("DELETE", "/api/v1/me/profile/skin", null, token),
                            updated -> {
                                preview.setImage(null);
                                status.setText("已恢复默认皮肤，请重新进服。");
                            })), newPassword,
                    button("修改密码", () -> {
                        var payload = json("old_password", confirmPassword.getText(), "new_password", newPassword.getText());
                        run(() -> NomiService.request("PUT", "/api/v1/me/password", payload, token), updated -> {
                            managementToken = null;
                            resetForm();
                            status.setText("密码已修改，旧会话已撤销，请重新登录。");
                        });
                    }), status);
            status.setText("登录成功");
        });
    }

    /// Stops private polling and clears local secrets when HMCL closes the dialog.
    @Override
    public void onDialogClosed() {
        closed = true;
        polling.stop();
        password.clear();
        repeated.clear();
        managementToken = null;
        pollToken = null;
    }
}

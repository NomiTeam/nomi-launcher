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
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AccountID;
import org.jackhuang.hmcl.auth.ClassicAccount;
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

/// Passwordless Nomi registration, QQ login and account management.
@NotNullByDefault
public final class NomiAccountPane extends JFXDialogLayout implements DialogAware {
    /// Stable login name, independent of the changeable game name.
    private final TextField username = new TextField();
    /// User-visible progress and errors.
    private final Label status = new Label();
    /// Scrollable form content.
    private final VBox body = new VBox(10);
    /// Serial, cancellable status polling schedule.
    private final Timeline polling = new Timeline();
    /// Short-lived management token, never persisted.
    private @Nullable String managementToken;
    /// Private application status/exchange credential, never sent to the group.
    private @Nullable String pollToken;
    /// Current application ID.
    private @Nullable String requestId;
    /// Distinguishes initial registration from existing-account login.
    private boolean registering;
    /// Prevents overlapping network operations.
    private boolean busy;
    /// Ignores callbacks after dialog closure.
    private boolean closed;

    /// Creates separate existing-login and registration choices with no password field.
    public NomiAccountPane() {
        setHeading(new Label("Nomi 账号"));
        username.setPromptText("注册时填写用户名；登录时填写固定登录名");
        status.setWrapText(true);
        resetForm();
        var scroll = new ScrollPane(body);
        scroll.setFitToWidth(true);
        scroll.setMaxHeight(520);
        setBody(scroll);
        setActions(button("关闭", () -> fireEvent(new DialogCloseEvent())));
        setPrefWidth(560);
    }

    /// Creates a full-width action button.
    private Button button(String text, Runnable action) {
        var button = new Button(text);
        button.setMaxWidth(Double.MAX_VALUE);
        button.setOnAction(event -> action.run());
        return button;
    }

    /// Runs requests off the UI thread and releases the form on success or failure.
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
                if (requestId != null && polling.getStatus() != Timeline.Status.RUNNING) resetForm();
            } else success.accept(result);
        }));
    }

    /// Builds JSON text properties without exposing tokens in URLs.
    private JsonObject json(String... pairs) {
        var json = new JsonObject();
        for (int i = 0; i < pairs.length; i += 2) json.addProperty(pairs[i], pairs[i + 1]);
        return json;
    }

    /// Starts one single-code application for a new or existing account.
    private void startRequest(boolean registration) {
        String name = username.getText().trim();
        String path = registration ? "/api/v1/registrations" : "/api/v1/logins";
        var payload = json(registration ? "name" : "username", name);
        run(() -> NomiService.request("POST", path, payload, null), result -> startPolling(result, registration));
    }

    /// Displays the group challenge and polls privately until it is validated.
    private void startPolling(JsonObject result, boolean registration) {
        requestId = result.get("request_id").getAsString();
        pollToken = result.get("poll_token").getAsString();
        registering = registration;
        String group = result.get("group_label").getAsString();
        var command = new TextField((registration ? "注册 " : "登录 ") + result.get("code").getAsString());
        command.setEditable(false);
        body.getChildren().setAll(new Label("在" + group + "发送下方命令；机器人未响应时可艾特它。"),
                command, status, button("取消申请", this::cancel));
        status.setText(registration ? "等待群验证注册，验证码只使用一次。" : "请使用该账号绑定的 QQ 在群内验证登录。");
        long expires = result.get("expires_at").getAsLong();
        polling.getKeyFrames().setAll(new KeyFrame(Duration.seconds(2), event -> {
            if (java.time.Instant.now().getEpochSecond() >= expires) {
                polling.stop();
                resetForm();
                status.setText("验证码已过期，请重新申请。");
            } else poll();
        }));
        polling.setCycleCount(Timeline.INDEFINITE);
        polling.play();
    }

    /// Resolves the private application path from its operation kind.
    private String applicationPath() {
        return "/api/v1/" + (registering ? "registrations/" : "logins/") + requestId;
    }

    /// Polls without overlapping requests; terminal verification proceeds to one-time session exchange.
    private void poll() {
        if (requestId == null || pollToken == null) return;
        String path = applicationPath();
        String token = pollToken;
        run(() -> NomiService.request("GET", path, null, token), result -> {
            String state = result.get("status").getAsString();
            if ("PENDING".equals(state)) return;
            polling.stop();
            if (registering && "COMPLETED".equals(state) || !registering && "VERIFIED".equals(state))
                exchange();
            else {
                resetForm();
                status.setText("申请状态：" + state + "，请重新发起验证。");
            }
        });
    }

    /// Claims tokens once, then constructs HMCL's external account directly from the returned session.
    private void exchange() {
        if (requestId == null || pollToken == null) return;
        String path = applicationPath() + "/session";
        String token = pollToken;
        @Nullable AccountID existingID = savedAccountID();
        run(() -> {
            // Resolve the server before consuming the grant, so a metadata failure leaves it claimable.
            var server = AuthlibInjectorServer.locateServer(NomiService.AUTH);
            if (!server.getUrl().replaceAll("/+$", "").equals(NomiService.AUTH.replaceAll("/+$", "")))
                throw new java.io.IOException("认证地址与 Nomi 配置不一致");
            var result = NomiService.request("POST", path, null, token);
            return new LoginResult(NomiService.accountFromSession(server, result, existingID),
                    result.get("management_token").getAsString());
        }, result -> {
            if (Accounts.isAccountFilesReadOnly(result.account())) {
                resetForm();
                status.setText("账号文件只读，请处理存储权限后重新验证登录。");
                return;
            }
            int index = Accounts.getAccounts().indexOf(result.account());
            if (index >= 0) Accounts.getAccounts().set(index, result.account());
            else Accounts.getAccounts().add(result.account());
            Accounts.setSelectedAccount(result.account());
            managementToken = result.token();
            requestId = null;
            pollToken = null;
            showManagement();
        });
    }

    /// Reads existing account IDs on the FX thread so repeat login replaces rather than duplicates a record.
    private @Nullable AccountID savedAccountID() {
        for (Account account : Accounts.getAccounts()) {
            if (NomiService.permits(account) && account instanceof ClassicAccount classic
                    && classic.getLoginName().equalsIgnoreCase(username.getText().trim()))
                return account.getAccountID();
        }
        return null;
    }

    /// A logged-in external account and its independent management token.
    @NotNullByDefault
    private record LoginResult(Account account, String token) { }

    /// Cancels an unused application before restoring both entry choices.
    private void cancel() {
        if (requestId == null || pollToken == null) return;
        String path = applicationPath();
        String token = pollToken;
        run(() -> NomiService.request("DELETE", path, null, token), result -> {
            polling.stop();
            resetForm();
            status.setText("申请已取消");
        });
    }

    /// Restores separate login and registration buttons without storing private application credentials.
    private void resetForm() {
        requestId = null;
        pollToken = null;
        body.getChildren().setAll(username, button("登录已有账号", () -> startRequest(false)),
                button("创建新账号", () -> startRequest(true)), status);
    }

    /// Manages game-name and skin changes through the QQ-authenticated session.
    private void showManagement() {
        String token = managementToken == null ? "" : managementToken;
        run(() -> NomiService.request("GET", "/api/v1/me", null, token), result -> {
            var profile = result.getAsJsonObject("profile");
            var gameName = new TextField(profile.get("name").getAsString());
            var model = new ComboBox<String>();
            model.getItems().setAll("default", "slim");
            model.setValue(profile.get("skin_model").getAsString());
            var preview = new ImageView();
            preview.setFitWidth(128);
            preview.setPreserveRatio(true);
            preview.setSmooth(false);
            if (!profile.get("skin_hash").isJsonNull())
                preview.setImage(new Image(NomiService.BASE + "/textures/" + profile.get("skin_hash").getAsString(), true));
            body.getChildren().setAll(new Label("固定登录名：" + result.get("login_name").getAsString()),
                    new Label("UUID：" + profile.get("uuid").getAsString()), gameName,
                    button("修改游戏用户名", () -> {
                        var payload = json("name", gameName.getText().trim());
                        run(() -> NomiService.request("PATCH", "/api/v1/me/profile/name", payload, token), updated ->
                                status.setText("改名成功，请关闭游戏并重新登录；UUID 保持不变。"));
                    }), new Label("当前皮肤纹理"), preview, model,
                    button("上传或更换皮肤", () -> {
                        var chooser = new FileChooser();
                        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter("PNG 皮肤", "*.png"));
                        @Nullable File file = chooser.showOpenDialog(getScene().getWindow());
                        if (file != null) {
                            String selectedModel = java.util.Objects.requireNonNullElse(model.getValue(), "default");
                            run(() -> NomiService.upload(file.toPath(), selectedModel, token), updated -> {
                                preview.setImage(new Image(updated.get("url").getAsString(), true));
                                status.setText("皮肤已更新，请重新进服。");
                            });
                        }
                    }), button("恢复默认皮肤", () ->
                            run(() -> NomiService.request("DELETE", "/api/v1/me/profile/skin", null, token), updated -> {
                                preview.setImage(null);
                                status.setText("已恢复默认皮肤，请重新进服。");
                            })), button("重新通过群验证码登录", () -> {
                        managementToken = null;
                        resetForm();
                        status.setText("输入固定登录名后点击登录。");
                    }), status);
            status.setText("群验证登录成功");
        });
    }

    /// Stops polling and clears private in-memory credentials.
    @Override
    public void onDialogClosed() {
        closed = true;
        polling.stop();
        managementToken = null;
        pollToken = null;
    }
}

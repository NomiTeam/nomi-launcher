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
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXTextField;
import javafx.geometry.Pos;
import javafx.geometry.Insets;
import javafx.scene.Cursor;
import javafx.scene.layout.StackPane;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.HintPane;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// Passwordless Nomi registration, QQ login and account management.
@NotNullByDefault
public final class NomiAccountPane extends JFXDialogLayout implements DialogAware {
    /// Stable login name, independent of the changeable game name.
    private final JFXTextField username = new JFXTextField();
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
        getStyleClass().add("microsoft-login-dialog");
        var heading = new Label("Nomi 群验证码登录");
        heading.getStyleClass().add("header-label");
        setHeading(heading);
        username.setPromptText("游戏用户名");
        username.setLabelFloat(true);
        body.setPadding(new Insets(16, 8, 8, 8));
        body.setAlignment(Pos.TOP_CENTER);
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
        var button = new JFXButton(text);
        button.getStyleClass().add("dialog-accept");
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
        var payload = registration ? json("name", name) : new JsonObject();
        run(() -> NomiService.request("POST", path, payload, null), result -> startPolling(result, registration));
    }

    /// Displays the group challenge and polls privately until it is validated.
    private void startPolling(JsonObject result, boolean registration) {
        requestId = result.get("request_id").getAsString();
        pollToken = result.get("poll_token").getAsString();
        registering = registration;
        String group = result.get("group_label").getAsString();
        String value = result.get("code").getAsString();
        String command = (registration ? "注册 " : "登录 ") + value;
        var codeLabel = new Label(value);
        codeLabel.getStyleClass().add("code-label");
        codeLabel.setStyle("-fx-font-family: monospace;");
        var codeBox = new StackPane(codeLabel);
        codeBox.getStyleClass().add("code-box");
        codeBox.setMaxWidth(USE_PREF_SIZE);
        codeBox.setCursor(Cursor.HAND);
        FXUtils.onClicked(codeBox, () -> FXUtils.copyText(command));
        var hint = new HintPane();
        hint.setText("完整命令已复制到剪贴板。在" + group + "粘贴发送：" + command
                + "。如果机器人没有响应，可以艾特它。验证码到期后可重新获取。");
        body.getChildren().setAll(hint, codeBox, status, button("取消申请", this::cancel));
        status.setText("等待群验证……");
        FXUtils.copyText(command);
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
        run(() -> {
            // Resolve the server before consuming the grant, so a metadata failure leaves it claimable.
            var server = AuthlibInjectorServer.locateServer(NomiService.AUTH);
            if (!server.getUrl().replaceAll("/+$", "").equals(NomiService.AUTH.replaceAll("/+$", "")))
                throw new java.io.IOException("认证地址与 Nomi 配置不一致");
            var result = NomiService.request("POST", path, null, token);
            return new LoginResult(NomiService.accountFromSession(server, result),
                    result.get("management_token").getAsString(), result, server);
        }, result -> {
            if (Accounts.isAccountFilesReadOnly(result.account())) {
                resetForm();
                status.setText("账号文件只读，请处理存储权限后重新验证登录。");
                return;
            }
            int index = -1;
            for (int i = 0; i < Accounts.getAccounts().size(); i++) {
                Account saved = Accounts.getAccounts().get(i);
                if (NomiService.permits(saved) && saved.getProfileID().equals(result.account().getProfileID())) {
                    index = i;
                    break;
                }
            }
            Account selected = result.account();
            if (index >= 0) {
                selected = NomiService.accountFromSession(result.server(), result.session(),
                        Accounts.getAccounts().get(index).getAccountID());
                Accounts.getAccounts().set(index, selected);
            } else Accounts.getAccounts().add(selected);
            Accounts.setSelectedAccount(selected);
            managementToken = result.token();
            requestId = null;
            pollToken = null;
            showManagement();
        });
    }

    /// A logged-in external account and its independent management token.
    @NotNullByDefault
    private record LoginResult(Account account, String token, JsonObject session, AuthlibInjectorServer server) { }

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

    /// Shows HMCL-style entry choices; only registration requires a name.
    private void resetForm() {
        requestId = null;
        pollToken = null;
        registering = false;
        var hint = new HintPane();
        hint.setText("点击获取登录码，在群里发送命令即可登录。机器人会自动找到你的账号，无需输入账号或密码。");
        body.getChildren().setAll(hint, button("获取登录码", () -> startRequest(false)),
                button("注册新账号", this::showRegistration), status);
    }

    /// Switches to the external-login-style username field used for registration.
    private void showRegistration() {
        polling.stop();
        requestId = null;
        pollToken = null;
        registering = true;
        var hint = new HintPane();
        hint.setText("填写游戏用户名，然后在指定群发送注册验证码。一个群成员只能创建一个账号。");
        body.getChildren().setAll(hint, username, button("获取注册验证码", () -> startRequest(true)),
                button("返回登录", this::resetForm), status);
    }

    /// Keeps skin operations in HMCL's native account row; this dialog only manages the game name.
    private void showManagement() {
        String token = managementToken == null ? "" : managementToken;
        run(() -> NomiService.request("GET", "/api/v1/me", null, token), result -> {
            var profile = result.getAsJsonObject("profile");
            var gameName = new JFXTextField(profile.get("name").getAsString());
            gameName.setPromptText("游戏用户名");
            gameName.setLabelFloat(true);
            var hint = new HintPane();
            hint.setText("登录成功。关闭此窗口后，使用账号卡片上的原生皮肤按钮上传皮肤；手臂模型由 HMCL 自动判断。");
            body.getChildren().setAll(hint, gameName, button("修改游戏用户名", () -> {
                var payload = json("name", gameName.getText().trim());
                run(() -> NomiService.request("PATCH", "/api/v1/me/profile/name", payload, token), updated ->
                        status.setText("改名成功，请重新登录并启动游戏；UUID 保持不变。"));
            }), status);
            status.setText("群验证登录成功");
        });
    }

    /// Immediately starts a name-free login challenge when the dialog opens.
    @Override
    public void onDialogShown() {
        if (!busy && requestId == null && managementToken == null) startRequest(false);
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

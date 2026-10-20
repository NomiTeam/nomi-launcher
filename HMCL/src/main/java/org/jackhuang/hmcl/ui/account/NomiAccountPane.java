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
import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Label;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.DialogAware;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.HintPane;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/// One group-code page for both existing login and registration performed by the bot.
@NotNullByDefault
public final class NomiAccountPane extends JFXDialogLayout implements DialogAware {
    /// Current application progress and errors.
    private final Label status = new Label("正在获取验证码……");
    /// Fixed content shared by every application state.
    private final VBox body = new VBox(18);
    /// Serial status polling schedule.
    private final Timeline polling = new Timeline();
    /// Private polling credential, never sent to the group.
    private @Nullable String pollToken;
    /// Current application identifier.
    private @Nullable String requestId;
    /// Prevents overlapping network operations.
    private boolean busy;
    /// Ignores callbacks after dialog closure.
    private volatile boolean closed;
    /// Indicates that a session was imported and must not be cancelled.
    private boolean completed;
    /// Extra controls appear only when recovery is needed, leaving the normal code page uncluttered.
    private final JFXButton retry = new JFXButton("重试");
    /// Starts another application after cancelling the previous one.
    private final JFXButton renew = new JFXButton("重新获取验证码");
    /// Displays the server-provided code lifetime independently of progress messages.
    private final Label remaining = new Label();
    /// Retains the failed operation so a transient error does not require another group command.
    private @Nullable Runnable retryAction;
    /// Server-provided expiry timestamp of the current application.
    private long expiresAt;

    /// Creates the HMCL code display with a single close action.
    public NomiAccountPane() {
        getStyleClass().add("microsoft-login-dialog");
        var heading = new Label("Nomi 群验证码登录");
        heading.getStyleClass().add("header-label");
        setHeading(heading);
        body.setPadding(new Insets(16, 8, 8, 8));
        body.setAlignment(Pos.TOP_CENTER);
        status.setWrapText(true);
        body.getChildren().setAll(status);
        setBody(body);
        var close = new JFXButton("关闭");
        close.getStyleClass().add("dialog-accept");
        close.setOnAction(event -> fireEvent(new DialogCloseEvent()));
        retry.setOnAction(event -> { if (retryAction != null) retryAction.run(); });
        renew.setOnAction(event -> restartRequest());
        retry.setVisible(false);
        retry.setManaged(false);
        renew.setVisible(false);
        renew.setManaged(false);
        setActions(retry, renew, close);
        setPrefWidth(560);
    }

    /// Runs bounded network operations without freezing or disabling the close action.
    private <T> void run(Callable<T> action, Consumer<T> success) {
        if (busy || closed) return;
        busy = true;
        retryAction = () -> run(action, success);
        showRecovery(false, false);
        CompletableFuture.supplyAsync(() -> {
            try { return action.call(); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        }).whenComplete((result, failure) -> Platform.runLater(() -> {
            busy = false;
            if (closed) {
                if (result instanceof JsonObject application && application.has("poll_token")) {
                    CompletableFuture.runAsync(() -> cancelRequest(application.get("request_id").getAsString(),
                            application.get("poll_token").getAsString()));
                } else if (result instanceof LoginResult login) {
                    CompletableFuture.runAsync(() -> discardSession(login.session()));
                }
                return;
            }
            if (failure != null) {
                polling.stop();
                @Nullable Throwable cause = failure.getCause();
                status.setText(cause == null ? "请求失败，请关闭后重新获取验证码。" : cause.getMessage());
                showRecovery(true, true);
            } else {
                try { success.accept(result); }
                catch (RuntimeException exception) {
                    status.setText("账号响应无法处理，请重新获取验证码。");
                    showRecovery(false, true);
                }
            }
        }));
    }

    /// Shows recovery controls only for errors and terminal application states.
    private void showRecovery(boolean canRetry, boolean canRenew) {
        retry.setVisible(canRetry);
        retry.setManaged(canRetry);
        renew.setVisible(canRenew);
        renew.setManaged(canRenew);
    }

    /// Cancels the old code before creating a replacement, with no overlapping application requests.
    private void restartRequest() {
        if (busy || closed) return;
        polling.stop();
        @Nullable String oldId = requestId;
        @Nullable String oldToken = pollToken;
        requestId = null;
        pollToken = null;
        body.getChildren().setAll(status);
        status.setText("正在获取验证码……");
        run(() -> {
            if (oldId != null && oldToken != null) cancelRequest(oldId, oldToken);
            return NomiService.request("POST", "/api/v1/logins", new JsonObject(), null);
        }, this::startPolling);
    }

    /// Creates the same unnamed application for all users.
    private void startRequest() {
        run(() -> NomiService.request("POST", "/api/v1/logins", new JsonObject(), null), this::startPolling);
    }

    /// Shows a clickable code and copies the complete login command automatically.
    private void startPolling(JsonObject result) {
        requestId = result.get("request_id").getAsString();
        pollToken = result.get("poll_token").getAsString();
        String value = result.get("code").getAsString();
        String command = "登录 " + value;
        var codeLabel = new Label(value);
        codeLabel.getStyleClass().add("code-label");
        codeLabel.setStyle("-fx-font-family: monospace;");
        var codeBox = new StackPane(codeLabel);
        codeBox.getStyleClass().add("code-box");
        codeBox.setMaxWidth(USE_PREF_SIZE);
        codeBox.setCursor(Cursor.HAND);
        FXUtils.onClicked(codeBox, () -> FXUtils.copyText(command));
        var hint = new HintPane();
        hint.setText("完整命令已复制到剪贴板。在" + result.get("group_label").getAsString()
                + "粘贴发送：" + command + "。没有账号时，按机器人的提示在群内发送 注册 <id>。关闭此窗口会取消申请。");
        body.getChildren().setAll(hint, codeBox, status, remaining);
        status.setText("等待群验证……");
        FXUtils.copyText(command);
        expiresAt = result.get("expires_at").getAsLong();
        updateRemaining();
        polling.getKeyFrames().setAll(new KeyFrame(Duration.seconds(1), event -> {
            long seconds = updateRemaining();
            if (seconds == 0 && !busy) {
                polling.stop();
                status.setText("验证码已过期，请重新获取。");
                showRecovery(false, true);
            } else if (seconds > 0 && seconds % 2 == 0) poll();
        }));
        polling.setCycleCount(Timeline.INDEFINITE);
        polling.play();
    }

    /// Updates the countdown from the absolute server expiry rather than counting UI timer ticks.
    private long updateRemaining() {
        long seconds = Math.max(0, expiresAt - java.time.Instant.now().getEpochSecond());
        remaining.setText("验证码剩余 " + seconds / 60 + " 分 " + seconds % 60 + " 秒");
        return seconds;
    }

    /// Polls privately while the group member logs in or supplies a new account name.
    private void poll() {
        if (requestId == null || pollToken == null) return;
        String path = "/api/v1/logins/" + requestId;
        String token = pollToken;
        run(() -> NomiService.request("GET", path, null, token), result -> {
            String state = result.get("status").getAsString();
            if ("PENDING".equals(state)) {
                polling.play();
                return;
            }
            if ("AWAITING_NAME".equals(state)) {
                status.setText("请在群内按机器人提示发送：注册 <id>");
                polling.play();
                return;
            }
            polling.stop();
            if ("VERIFIED".equals(state)) exchange();
            else {
                String message = "申请已结束，请关闭后重新获取验证码。";
                if (result.has("error") && !result.get("error").isJsonNull()) {
                    message = switch (result.get("error").getAsString()) {
                        case "NAME_TAKEN" -> "ID 已被占用，申请已关闭。";
                        case "INVALID_NAME" -> "ID 格式不正确，申请已关闭。";
                        default -> message;
                    };
                }
                status.setText(message);
                showRecovery(false, true);
            }
        });
    }

    /// Claims a session, preserves an existing record by UUID and closes on success.
    private void exchange() {
        if (requestId == null || pollToken == null) return;
        String path = "/api/v1/logins/" + requestId + "/session";
        String token = pollToken;
        run(() -> {
            var server = AuthlibInjectorServer.locateServer(NomiService.AUTH);
            if (!server.getUrl().replaceAll("/+$", "").equals(NomiService.AUTH.replaceAll("/+$", "")))
                throw new java.io.IOException("认证地址与 Nomi 配置不一致");
            if (closed) throw new java.io.IOException("申请已取消");
            var session = NomiService.request("POST", path, null, token);
            return new LoginResult(NomiService.accountFromSession(server, session), session, server);
        }, result -> {
            if (Accounts.isAccountFilesReadOnly(result.account())) {
                CompletableFuture.runAsync(() -> discardSession(result.session()));
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
            Account pending = selected;
            retryAction = () -> finishSavedAccount(pending);
            finishSavedAccount(selected);
        });
    }

    /// Confirms disk persistence before dismissing the successful application.
    private void finishSavedAccount(Account selected) {
        try { org.jackhuang.hmcl.setting.SettingsManager.persistPortableCredentials(selected); }
        catch (java.io.IOException exception) {
            status.setText("无法保存登录凭据，请检查磁盘空间和目录权限后重试。");
            showRecovery(true, false);
            return;
        }
        completed = true;
        requestId = null;
        pollToken = null;
        fireEvent(new DialogCloseEvent());
    }

    /// A QQ-authenticated account and its raw session needed for record replacement.
    @NotNullByDefault
    private record LoginResult(Account account, JsonObject session, AuthlibInjectorServer server) { }

    /// Cancels an unclaimed application off the UI thread; TTL covers transport failure.
    private static void cancelRequest(String id, String token) {
        try { NomiService.request("DELETE", "/api/v1/logins/" + id, null, token); }
        catch (Exception ignored) { /* The short-lived application will expire if unreachable. */ }
    }

    /// Revokes a session delivered after closure or when portable storage is read-only.
    private static void discardSession(JsonObject session) {
        try {
            if (session.has("refresh_token"))
                NomiService.request("DELETE", "/api/v1/sessions/refresh", null,
                        session.get("refresh_token").getAsString());
            NomiService.request("POST", "/api/yggdrasil/authserver/invalidate", session.getAsJsonObject("game"), null);
            NomiService.request("DELETE", "/api/v1/sessions/current", null,
                    session.get("management_token").getAsString());
        } catch (Exception ignored) { /* Tokens remain bounded by their server-side TTL. */ }
    }

    /// Starts the only entry page as soon as the dialog is shown.
    @Override
    public void onDialogShown() {
        if (!busy && requestId == null) startRequest();
    }

    /// Closing is cancellation for pending requests and a simple close after success.
    @Override
    public void onDialogClosed() {
        closed = true;
        polling.stop();
        if (!completed && requestId != null && pollToken != null) {
            String id = requestId;
            String token = pollToken;
            CompletableFuture.runAsync(() -> cancelRequest(id, token));
        }
        requestId = null;
        pollToken = null;
    }
}

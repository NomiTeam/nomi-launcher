/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2020  huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.ui;

import org.jackhuang.hmcl.auth.*;
import org.jetbrains.annotations.Nullable;

import java.util.concurrent.CancellationException;

import static org.jackhuang.hmcl.ui.FXUtils.runInFX;

public final class DialogController {
    private DialogController() {
    }

    /// Opens group-code verification on the UI thread and waits asynchronously for a saved account.
    public static java.util.concurrent.CompletableFuture<Account> requestNomiLogin() {
        var result = new java.util.concurrent.CompletableFuture<Account>();
        runInFX(() -> {
            if (result.isDone()) return;
            var pane = new org.jackhuang.hmcl.ui.account.NomiAccountPane();
            pane.loginResult().whenComplete((@Nullable Account account, @Nullable Throwable failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(java.util.Objects.requireNonNull(account));
            });
            result.whenComplete((@Nullable Account account, @Nullable Throwable failure) -> {
                if (result.isCancelled()) runInFX(() -> pane.fireEvent(new org.jackhuang.hmcl.ui.construct.DialogCloseEvent()));
            });
            Controllers.dialog(pane);
        });
        return result;
    }

    /// Opens QQ verification instead of any upstream credential dialog.
    public static AuthInfo logIn(Account account) throws CancellationException, AuthenticationException, InterruptedException {
        if (!org.jackhuang.hmcl.ui.account.NomiService.permits(account))
            throw new AuthenticationException("当前启动器只支持 Nomi 账号");
        runInFX(() -> Controllers.dialog(new org.jackhuang.hmcl.ui.account.NomiAccountPane()));
        throw new CancellationException("请通过群验证码重新登录后再次操作");
    }
}

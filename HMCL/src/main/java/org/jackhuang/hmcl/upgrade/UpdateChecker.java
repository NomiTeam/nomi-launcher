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
package org.jackhuang.hmcl.upgrade;

import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.binding.BooleanBinding;
import javafx.beans.property.*;
import javafx.beans.value.ObservableBooleanValue;
import org.jackhuang.hmcl.Metadata;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;

import static org.jackhuang.hmcl.setting.SettingsManager.settings;
import static org.jackhuang.hmcl.util.Lang.*;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public final class UpdateChecker {
    private UpdateChecker() {
    }

    private static final ObjectProperty<@Nullable RemoteVersion> latestVersion = new SimpleObjectProperty<>();
    private static final BooleanBinding outdated = Bindings.createBooleanBinding(
            () -> {
                @Nullable RemoteVersion latest = latestVersion.get();
                return latest != null && NomiReleaseService.newer(Metadata.VERSION, latest.version());
            },
            latestVersion);
    private static final ReadOnlyBooleanWrapper checkingUpdate = new ReadOnlyBooleanWrapper(false);
    private static final ReadOnlyObjectWrapper<@Nullable Throwable> error = new ReadOnlyObjectWrapper<>();

    /// Nomi releases are checked independently of the upstream update service.
    public static final boolean ENABLED = true;

    /// Initializes update checking only when a Nomi update service is available.
    public static void init() {
        requestCheckUpdate(UpdateChannel.STABLE, false);
    }

    public static @Nullable RemoteVersion getLatestVersion() {
        return latestVersion.get();
    }

    public static ReadOnlyObjectProperty<@Nullable RemoteVersion> latestVersionProperty() {
        return latestVersion;
    }

    public static boolean isOutdated() {
        return outdated.get();
    }

    public static ObservableBooleanValue outdatedProperty() {
        return outdated;
    }

    public static boolean isCheckingUpdate() {
        return checkingUpdate.get();
    }

    public static ReadOnlyBooleanProperty checkingUpdateProperty() {
        return checkingUpdate.getReadOnlyProperty();
    }

    public static ReadOnlyObjectProperty<@Nullable Throwable> errorProperty() {
        return error.getReadOnlyProperty();
    }

    /// Fetches only stable Nomi releases, ignoring legacy preview preferences.
    private static @Nullable RemoteVersion checkUpdate(UpdateChannel channel, boolean preview) throws IOException {
        return NomiReleaseService.fetch(false);
    }

    /// Checks GitHub Releases asynchronously and reports transport failures without replacing the launcher.
    public static void requestCheckUpdate(UpdateChannel channel, boolean preview) {
        if (!ENABLED) return;
        Platform.runLater(() -> {
            if (isCheckingUpdate())
                return;
            checkingUpdate.set(true);

            thread(() -> {
                @Nullable RemoteVersion result = null;
                @Nullable Throwable t = null;
                try {
                    result = checkUpdate(channel, preview);
                    LOG.info("Latest version (" + channel + ", preview=" + preview + ") is " + result);
                } catch (Throwable e) {
                    t = e;
                    LOG.warning("Failed to check for update", e);
                }
                @Nullable Throwable throwable = t;

                @Nullable RemoteVersion finalResult = result;
                Platform.runLater(() -> {
                    if (throwable != null) {
                        latestVersion.set(null);
                        error.set(throwable);
                    } else {
                        error.set(null);
                        latestVersion.set(finalResult);
                    }
                    checkingUpdate.set(false);
                });
            }, "Update Checker", true);
        });
    }
}

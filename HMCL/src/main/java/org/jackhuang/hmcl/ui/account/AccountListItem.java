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

import javafx.beans.binding.Bindings;
import javafx.beans.binding.ObjectBinding;
import javafx.beans.binding.StringBinding;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.beans.value.ObservableBooleanValue;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Skin;
import javafx.scene.image.Image;
import javafx.stage.FileChooser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.NomiAuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.ClassicAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jackhuang.hmcl.auth.offline.OfflineAccount;
import org.jackhuang.hmcl.auth.yggdrasil.CompleteGameProfile;
import org.jackhuang.hmcl.auth.yggdrasil.TextureType;
import org.jackhuang.hmcl.setting.Accounts;
import org.jackhuang.hmcl.task.Schedulers;
import org.jackhuang.hmcl.task.Task;
import org.jackhuang.hmcl.ui.Controllers;
import org.jackhuang.hmcl.ui.construct.MessageDialogPane.MessageType;
import org.jackhuang.hmcl.util.StringUtils;
import org.jackhuang.hmcl.util.skin.InvalidSkinException;
import org.jackhuang.hmcl.util.skin.NormalizedSkin;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CancellationException;

import static java.util.Collections.emptySet;
import static javafx.beans.binding.Bindings.createBooleanBinding;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;
import static org.jackhuang.hmcl.util.logging.Logger.LOG;

public class AccountListItem extends RadioButton {

    private final Account account;
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty subtitle = new SimpleStringProperty();

    /// Builds an account card, showing only the current profile ID for Nomi accounts.
    public AccountListItem(Account account) {
        this.account = account;
        getStyleClass().clear();
        setUserData(account);

        String loginTypeName = Accounts.getLocalizedLoginTypeName(Accounts.getAccountFactory(account));
        String portableSuffix = account.isPortable() ? ", " + i18n("account.portable") : "";
        if (account instanceof AuthlibInjectorAccount) {
            AuthlibInjectorServer server = ((AuthlibInjectorAccount) account).getServer();
            subtitle.bind(Bindings.concat(
                    loginTypeName, ", ", i18n("account.injector.server"), ": ",
                    Bindings.createStringBinding(server::getName, server), portableSuffix));
        } else {
            subtitle.set(loginTypeName + portableSuffix);
        }

        StringBinding profileName = Bindings.createStringBinding(() -> {
            String name = account.getProfileName();
            return StringUtils.isBlank(name) ? account.getProfileID().toString() : name;
        }, account);
        if (account instanceof ClassicAccount classicAccount && !NomiService.permits(account)) {
            title.bind(Bindings.concat(profileName, " - ", classicAccount.getLoginName()));
        } else {
            title.bind(profileName);
        }
    }

    @Override
    protected Skin<?> createDefaultSkin() {
        return new AccountListItemSkin(this);
    }

    /// Refreshes the account and persists rotating credentials before dependent operations run.
    public Task<Account> refreshAsync() {
        return Task.composeAsync(() -> {
            account.clearCache();
            return Task.fromCompletableFuture(NomiService.verifiedAccount(account));
        });
    }

    public ObservableBooleanValue canUploadSkin() {
        if (account instanceof AuthlibInjectorAccount aiAccount) {
            ObjectBinding<Optional<CompleteGameProfile>> profile = aiAccount.getYggdrasilService().getProfileRepository().binding(aiAccount.getProfileID());
            return createBooleanBinding(() -> {
                Set<TextureType> uploadableTextures = profile.get()
                        .map(AuthlibInjectorAccount::getUploadableTextures)
                        .orElse(emptySet());
                return uploadableTextures.contains(TextureType.SKIN);
            }, profile);
        } else if (account instanceof OfflineAccount || account.canUploadSkin()) {
            return createBooleanBinding(() -> true);
        } else {
            return createBooleanBinding(() -> false);
        }
    }

    /**
     * @return the skin upload task, null if no file is selected
     */
    @Nullable
    public Task<?> uploadSkin() {
        if (account instanceof OfflineAccount) {
            Controllers.dialog(new OfflineAccountSkinPane((OfflineAccount) account));
            return null;
        }
        if (!account.canUploadSkin()) {
            return null;
        }

        FileChooser chooser = new FileChooser();
        chooser.setTitle(i18n("account.skin.upload"));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(i18n("account.skin.file"), "*.png"));
        Path selectedFile = Controllers.showOpenDialog(chooser);
        if (selectedFile == null) {
            return null;
        }

        return refreshAsync()
                .thenApplyAsync(active -> {
                    Image skinImg;
                    try (var input = Files.newInputStream(selectedFile)) {
                        skinImg = new Image(input);
                    } catch (IOException e) {
                        throw new InvalidSkinException("Failed to read skin image", e);
                    }
                    if (skinImg.isError()) {
                        throw new InvalidSkinException("Failed to read skin image", skinImg.getException());
                    }
                    if (skinImg.getWidth() != 64 || (skinImg.getHeight() != 32 && skinImg.getHeight() != 64)) {
                        throw new InvalidSkinException("Invalid skin size");
                    }
                    NormalizedSkin skin = new NormalizedSkin(skinImg);
                    String model = skin.isSlim() ? "slim" : "";
                    LOG.info("Uploading skin [" + selectedFile + "], model [" + model + "]");
                    active.uploadSkin(skin.isSlim(), selectedFile);
                    active.clearCache();
                    return active;
                })
                .thenComposeAsync(active -> Task.fromCompletableFuture(NomiService.verifiedAccount(active)))
                .whenComplete(Schedulers.javafx(), (@Nullable Exception e) -> {
                    if (e != null && !(org.jackhuang.hmcl.util.Lang.resolveException(e) instanceof CancellationException)) {
                        Controllers.dialog(Accounts.localizeErrorMessage(e), i18n("account.skin.upload.failed"), MessageType.ERROR);
                    }
                });
    }

    /// Removes a writable account record and revokes its device refresh session when present.
    public void remove() {
        if (!Accounts.canRemoveAccount(account)) {
            Controllers.confirmBackupAndOverwrite(i18n("account.storage.read_only"), () -> {
                Accounts.forceOverwriteAccountFiles(account);
                removeStoredAccount();
            });
            return;
        }

        removeStoredAccount();
    }

    /// Prevents duplicate remote revocations while removal is in progress.
    private boolean removing;

    /// Removes the local record only after remote revocation succeeds, retaining a retry path on failure.
    private void removeStoredAccount() {
        if (removing) return;
        if (account instanceof NomiAuthlibInjectorAccount nomi) {
            removing = true;
            Task.runAsync(nomi::revokeSession).whenComplete(Schedulers.javafx(), (@Nullable Exception failure) -> {
                removing = false;
                if (failure == null) Accounts.getAccounts().remove(account);
                else Controllers.showToast("远端注销失败，账号尚未移除，请检查网络后重试。");
            }).start();
        } else Accounts.getAccounts().remove(account);
    }

    public Account getAccount() {
        return account;
    }

    public String getTitle() {
        return title.get();
    }

    public void setTitle(String title) {
        this.title.set(title);
    }

    public StringProperty titleProperty() {
        return title;
    }

    public String getSubtitle() {
        return subtitle.get();
    }

    public void setSubtitle(String subtitle) {
        this.subtitle.set(subtitle);
    }

    public StringProperty subtitleProperty() {
        return subtitle;
    }
}

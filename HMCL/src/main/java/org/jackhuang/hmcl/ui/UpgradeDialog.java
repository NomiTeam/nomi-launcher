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
package org.jackhuang.hmcl.ui;

import com.jfoenix.controls.JFXButton;
import com.jfoenix.controls.JFXDialogLayout;
import javafx.geometry.Insets;
import javafx.scene.control.Label;
import org.jackhuang.hmcl.Metadata;
import org.jackhuang.hmcl.ui.construct.DialogCloseEvent;
import org.jackhuang.hmcl.ui.construct.JFXHyperlink;
import org.jackhuang.hmcl.upgrade.RemoteVersion;

import static org.jackhuang.hmcl.ui.FXUtils.onEscPressed;
import static org.jackhuang.hmcl.util.i18n.I18n.i18n;

public final class UpgradeDialog extends JFXDialogLayout {

    /// Confirms a Nomi release update and links to its own release notes.
    public UpgradeDialog(RemoteVersion remoteVersion, Runnable updateRunnable) {
        maxWidthProperty().bind(Controllers.getDecorator().contentWidthProperty().multiply(0.7));
        maxHeightProperty().bind(Controllers.getDecorator().contentHeightProperty().multiply(0.7));

        Label heading = new Label(i18n("update.changelog"));
        heading.setPadding(new Insets(0, 0, 8, 0));
        setHeading(heading);
        String url = Metadata.MANUAL_UPDATE_URL + "/tag/v" + remoteVersion.version();
        var description = new Label("将更新 Nomi Launcher 至 " + remoteVersion.version()
                + "。下载后校验 SHA-256 和 Nomi 发布签名，完成后重启启动器。账号与游戏文件会保留。");
        description.setWrapText(true);
        setBody(description);
        JFXHyperlink openInBrowser = new JFXHyperlink(i18n("web.view_in_browser"));
        openInBrowser.setExternalLink(url);

        JFXButton updateButton = new JFXButton(i18n("update.accept"));
        updateButton.getStyleClass().add("dialog-accept");
        updateButton.setOnAction(e -> updateRunnable.run());

        JFXButton cancelButton = new JFXButton(i18n("button.cancel"));
        cancelButton.getStyleClass().add("dialog-cancel");
        cancelButton.setOnAction(e -> fireEvent(new DialogCloseEvent()));

        setActions(openInBrowser, updateButton, cancelButton);
        onEscPressed(this, cancelButton::fire);
    }
}

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
package org.jackhuang.hmcl.ui.main;

import javafx.scene.control.ScrollPane;
import javafx.scene.layout.VBox;
import org.jackhuang.hmcl.ui.FXUtils;
import org.jackhuang.hmcl.ui.construct.ComponentList;
import org.jackhuang.hmcl.ui.construct.LineButton;
import org.jackhuang.hmcl.ui.construct.SpinnerPane;
import org.jetbrains.annotations.NotNullByDefault;

/// Local Nomi usage instructions without fetching upstream promotional content.
@NotNullByDefault
public final class HelpPage extends SpinnerPane {
    /// Builds offline help and a link to the Nomi project documentation.
    public HelpPage() {
        var content = new VBox(12);
        content.getStyleClass().add("spinner-pane-content");
        var scroll = new ScrollPane(content);
        scroll.setFitToWidth(true);
        FXUtils.smoothScrolling(scroll);
        setContent(scroll);
        var guide = new ComponentList();
        var login = new LineButton();
        login.setTitle("群验证码登录");
        login.setSubtitle("添加账号后，在指定群发送自动复制的登录命令；没有账号时按机器人提示注册。无需密码。");
        var profile = new LineButton();
        profile.setTitle("改名与皮肤");
        profile.setSubtitle("在账号卡片使用改名和上传皮肤按钮，也可在群内发送改名命令。改名不改变角色 UUID。");
        var updates = new LineButton();
        updates.setTitle("启动器更新");
        updates.setSubtitle("在设置中检查 Nomi Launcher 更新。更新包由本项目的 GitHub Releases 提供。");
        var docs = LineButton.createExternalLinkButton("https://github.com/NomiTeam/nomi-launcher#readme");
        docs.setTitle("Nomi Launcher 使用说明");
        guide.getContent().setAll(login, profile, updates, docs);
        content.getChildren().setAll(guide);
    }
}

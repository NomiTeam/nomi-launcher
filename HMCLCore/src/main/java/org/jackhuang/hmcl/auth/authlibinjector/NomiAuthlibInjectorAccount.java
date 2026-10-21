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
package org.jackhuang.hmcl.auth.authlibinjector;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.AccountID;
import org.jackhuang.hmcl.auth.AuthInfo;
import org.jackhuang.hmcl.auth.AuthenticationException;
import org.jackhuang.hmcl.auth.CredentialExpiredException;
import org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession;
import org.jackhuang.hmcl.util.gson.JsonUtils;
import org.jackhuang.hmcl.util.logging.Logger;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/// Nomi device session with a private, rotating refresh credential that never enters game arguments.
@NotNullByDefault
public final class NomiAuthlibInjectorAccount extends AuthlibInjectorAccount {
    /// Private-only storage key recognized by the external account factory.
    public static final String REFRESH_TOKEN_KEY = "nomiRefreshToken";
    /// Bounded client that never forwards credentials across redirects.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    /// Current single-use refresh credential, stored only in private account data.
    private String refreshToken;

    /// Restores an external account and its separately scoped launcher credential.
    public NomiAuthlibInjectorAccount(AccountID id, AuthlibInjectorServer server,
            AuthlibInjectorArtifactProvider downloader, String loginName, YggdrasilSession session, String refreshToken) {
        super(id, server, downloader, loginName, session);
        this.refreshToken = refreshToken;
        Logger.registerAccessToken(refreshToken);
    }

    /// Validates the game session and rotates the launcher credential only when renewal is needed.
    public synchronized void ensureGameSession() throws AuthenticationException {
        try {
            var profileRequest = HttpRequest.newBuilder(refreshEndpoint().resolve("../game/profile"))
                    .timeout(Duration.ofSeconds(12)).header("Authorization", "Bearer " + session.getAccessToken()).GET().build();
            var profileResponse = HTTP.send(profileRequest, HttpResponse.BodyHandlers.ofString());
            if (profileResponse.statusCode() == 200) {
                var profile = JsonParser.parseString(profileResponse.body()).getAsJsonObject();
                if (!profileID.equals(org.glavo.uuid.UUIDs.parse(requireString(profile, "id"))))
                    throw new IOException("认证响应中的角色不匹配。");
                String name = requireString(profile, "name");
                if (!name.equals(getProfileName())) acceptProfileName(name);
                return;
            }
            if (profileResponse.statusCode() != 401 && profileResponse.statusCode() != 403)
                throw new IOException("账号服务暂时不可用，请稍后重试。");
            // Stable across retries and process restarts; only the matching spent credential can recover a grant.
            String requestId = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(("nomi-refresh-v1:" + refreshToken).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            var request = HttpRequest.newBuilder(refreshEndpoint()).timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + refreshToken)
                    .header("X-Request-ID", requestId)
                    .POST(HttpRequest.BodyPublishers.noBody()).build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401 || response.statusCode() == 403)
                throw new CredentialExpiredException("登录凭据已过期，请重新通过群验证码登录。");
            if (response.statusCode() != 200) throw new IOException("刷新服务暂时不可用，请稍后重试。");
            var result = JsonParser.parseString(response.body()).getAsJsonObject();
            var game = result.getAsJsonObject("game");
            var selected = game.getAsJsonObject("selectedProfile");
            var metadata = new JsonObject();
            metadata.addProperty("profileID", selected.get("id").getAsString());
            var privateData = new JsonObject();
            privateData.addProperty("accessToken", requireString(game, "accessToken"));
            privateData.addProperty("clientToken", requireString(game, "clientToken"));
            privateData.addProperty("profileName", requireString(selected, "name"));
            var renewed = YggdrasilSession.fromStorage(metadata, privateData);
            if (!profileID.equals(renewed.getSelectedProfile().getId())) throw new IOException("刷新响应中的角色不匹配。");
            String nextRefresh = requireString(result, "refresh_token");
            session = renewed;
            refreshToken = nextRefresh;
            Logger.registerAccessToken(nextRefresh);
            invalidate();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("刷新请求已中断。", exception);
        } catch (IOException | RuntimeException | java.security.NoSuchAlgorithmException exception) {
            throw new AuthenticationException("无法刷新登录会话，请检查网络后重试。", exception);
        }
    }

    /// Requires a nonempty response property without exposing response contents in errors.
    private static String requireString(JsonObject object, String key) throws IOException {
        @Nullable String value = JsonUtils.getString(object, key);
        if (value == null || value.isBlank()) throw new IOException("无效刷新响应。");
        return value;
    }

    /// Resolves the fixed refresh route on the authentication server's own origin.
    private URI refreshEndpoint() throws IOException {
        String root = getServer().getUrl().replaceAll("/+$", "");
        if (!root.endsWith("/api/yggdrasil")) throw new IOException("无效认证根路径。");
        return URI.create(root.substring(0, root.length() - "/api/yggdrasil".length()) + "/api/v1/sessions/refresh");
    }

    /// Refreshes the private launcher session before constructing ordinary game-only auth information.
    @Override
    public synchronized AuthInfo logIn() throws AuthenticationException {
        ensureGameSession();
        return inject(session::toAuthInfo);
    }

    /// Writes rotating credentials only to the launcher's private account file.
    @Override
    public synchronized void writePrivateData(JsonObject data) {
        super.writePrivateData(data);
        data.addProperty(REFRESH_TOKEN_KEY, refreshToken);
    }

    /// Applies a successful profile rename without replacing or losing a concurrently renewed credential.
    public synchronized void acceptProfileName(String name) {
        var metadata = new JsonObject();
        metadata.addProperty("profileID", profileID.toString());
        var data = new JsonObject();
        session.writePrivateData(data);
        data.addProperty("profileName", name);
        session = YggdrasilSession.fromStorage(metadata, data);
        invalidate();
    }

    /// Revokes this device session when an unclaimed login is discarded or the account is removed.
    public synchronized void revokeSession() throws AuthenticationException {
        try {
            var request = HttpRequest.newBuilder(refreshEndpoint()).timeout(Duration.ofSeconds(12))
                    .header("Authorization", "Bearer " + refreshToken).DELETE().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() != 204) throw new IOException("注销服务返回错误：" + response.statusCode());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AuthenticationException("注销请求已中断。", exception);
        } catch (IOException exception) {
            throw new AuthenticationException("注销请求失败。", exception);
        }
    }
}

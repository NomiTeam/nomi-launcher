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
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorAccount;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;

/// Nomi's fixed authentication endpoint and bounded HTTP client.
@NotNullByDefault
public final class NomiService {
    /// Public base URL; supply the deployment address through -Dnomi.api.base at release time.
    public static final String BASE = System.getProperty("nomi.api.base", "http://127.0.0.1:8000").replaceAll("/+$", "");
    /// Yggdrasil root used for account and launch validation.
    public static final String AUTH = BASE + "/api/yggdrasil/";
    /// Client does not follow redirects, so credentials cannot be forwarded to another endpoint.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();

    /// Utility class without instances.
    private NomiService() { }

    /// Accepts only accounts backed by the configured Nomi Yggdrasil root.
    public static boolean permits(@Nullable Account account) {
        return account instanceof AuthlibInjectorAccount injector
                && injector.getServer().getUrl().replaceAll("/+$", "").equals(AUTH.replaceAll("/+$", ""));
    }

    /// Sends JSON, returning an empty object for successful no-content responses.
    public static JsonObject request(String method, String path, @Nullable JsonObject body, @Nullable String token)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path)).timeout(Duration.ofSeconds(12));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8));
        return response(HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    /// Uploads a bounded PNG file using the same business API as other clients.
    public static JsonObject upload(Path file, String model, String token) throws IOException, InterruptedException {
        if (Files.size(file) > 1024 * 1024) throw new IOException("皮肤不能超过 1 MiB");
        String boundary = "Nomi" + java.util.UUID.randomUUID();
        byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"model\"\r\n\r\n"
                + model + "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"image\"; filename=\"skin.png\""
                + "\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        var output = new java.io.ByteArrayOutputStream();
        output.write(prefix);
        output.write(Files.readAllBytes(file));
        output.write(suffix);
        var request = HttpRequest.newBuilder(URI.create(BASE + "/api/v1/me/profile/skin"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .PUT(HttpRequest.BodyPublishers.ofByteArray(output.toByteArray())).build();
        return response(HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)));
    }

    /// Converts errors to a user-visible message without logging request credentials.
    private static JsonObject response(HttpResponse<String> response) throws IOException {
        JsonObject json;
        try {
            json = response.body().isBlank() ? new JsonObject() : JsonParser.parseString(response.body()).getAsJsonObject();
        } catch (RuntimeException exception) {
            throw new IOException("账号服务返回了无效响应");
        }
        if (response.statusCode() >= 300)
            throw new IOException(json.has("message") ? json.get("message").getAsString() : "账号服务请求失败：" + response.statusCode());
        return json;
    }
}

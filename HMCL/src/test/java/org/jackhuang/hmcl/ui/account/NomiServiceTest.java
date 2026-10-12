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

import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises bounded business requests and rejects redirects instead of forwarding bearer tokens.
@NotNullByDefault
class NomiServiceTest {
    /// In-process server with an ephemeral local port.
    private static @Nullable HttpServer server;

    /// Starts isolated routes before initializing the Nomi endpoint configuration.
    @BeforeAll
    static void setup() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/ok", exchange -> {
            byte[] content = "{\"status\":\"COMPLETED\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.createContext("/error", exchange -> {
            byte[] content = "{\"message\":\"expired\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400, content.length);
            exchange.getResponseBody().write(content);
            exchange.close();
        });
        server.createContext("/redirect", exchange -> {
            exchange.getResponseHeaders().set("Location", "/ok");
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });
        server.start();
        System.setProperty("nomi.api.base", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    /// Releases the local server after all tests.
    @AfterAll
    static void cleanup() {
        Objects.requireNonNull(server).stop(0);
        System.clearProperty("nomi.api.base");
    }

    /// Accepts JSON success and preserves a safe business error message.
    @Test
    void jsonAndError() throws Exception {
        assertEquals("COMPLETED", NomiService.request("GET", "/ok", null, "private").get("status").getAsString());
        assertEquals("expired", assertThrows(IOException.class,
                () -> NomiService.request("POST", "/error", null, "private")).getMessage());
    }

    /// A redirect must not succeed, even when the target route returns valid JSON.
    @Test
    void rejectsRedirectAndMissingAccount() {
        assertThrows(IOException.class, () -> NomiService.request("GET", "/redirect", null, "private"));
        assertFalse(NomiService.permits(null));
    }

    /// A QR-authenticated game session imports without password authentication and preserves local record identity.
    @Test
    void importsVerifiedSessionWithoutPassword() {
        var session = JsonParser.parseString("""
                {"login_name":"Alice","management_token":"management",
                 "game":{"accessToken":"game","clientToken":"client",
                         "selectedProfile":{"id":"12345678901234567890123456789012","name":"Alice"}}}
                """).getAsJsonObject();
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var account = NomiService.accountFromSession(server, session);
        assertTrue(NomiService.permits(account));
        assertEquals("Alice", account.getProfileName());
        var renewed = NomiService.accountFromSession(server, session, account.getAccountID());
        assertEquals(account, renewed);
    }
}

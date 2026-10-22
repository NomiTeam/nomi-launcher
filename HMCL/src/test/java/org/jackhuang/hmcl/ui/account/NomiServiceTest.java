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

import com.google.gson.JsonParser;
import com.google.gson.JsonObject;
import org.jackhuang.hmcl.auth.authlibinjector.NomiAuthlibInjectorAccount;
import org.jackhuang.hmcl.auth.CredentialExpiredException;
import org.jackhuang.hmcl.auth.Account;
import org.jackhuang.hmcl.auth.AuthenticationException;
import com.sun.net.httpserver.HttpServer;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javax.imageio.ImageIO;
import org.jackhuang.hmcl.JavaFXLauncher;
import org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorServer;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/// Exercises bounded business requests and rejects redirects instead of forwarding bearer tokens.
@NotNullByDefault
class NomiServiceTest {
    /// In-process server with an ephemeral local port.
    private static @Nullable HttpServer server;

    /// Signals that the creation response can be released.
    private static volatile CountDownLatch release = new CountDownLatch(0);
    /// Signals receipt of a creation request.
    private static volatile CountDownLatch entered = new CountDownLatch(1);
    /// Counts authenticated cancellation requests.
    private static final AtomicInteger cancelled = new AtomicInteger();


    /// Number of refresh grants consumed by the fake backend.
    private static final AtomicInteger refreshed = new AtomicInteger();

    /// Injects transient application creation failures for recovery UI tests.
    private static final AtomicInteger creationFailures = new AtomicInteger();
    /// Counts creation attempts independently of private polling.
    private static final AtomicInteger creationAttempts = new AtomicInteger();
    /// Lifetime used by the fake application creation route.
    private static volatile long codeLifetime = 300;
    /// Current name returned by an authenticated profile lookup.
    private static volatile String profileName = "Alice";
    /// Counts the legacy validation route to detect redundant validation.
    private static final AtomicInteger legacyValidations = new AtomicInteger();
    /// Captures idempotency keys across deliberately lost refresh responses.
    private static final java.util.List<String> lostResponseKeys = new java.util.concurrent.CopyOnWriteArrayList<>();

    /// Clipboard contents restored after rendering and command-copy verification.
    private static final Map<DataFormat, Object> savedClipboard = new HashMap<>();

    /// Selects one deliberately stalled session endpoint for monitor regression tests.
    private static volatile String slowSessionRoute = "";
    /// Signals that a session request reached its endpoint.
    private static volatile CountDownLatch sessionEntered = new CountDownLatch(0);
    /// Releases the stalled session response.
    private static volatile CountDownLatch sessionRelease = new CountDownLatch(0);

    /// Holds a fake response without blocking UI-side account snapshots.
    private static void stallSession(String route) {
        if (!route.equals(slowSessionRoute)) return;
        sessionEntered.countDown();
        try { sessionRelease.await(10, TimeUnit.SECONDS); }
        catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
    }

    /// Starts isolated routes before initializing the Nomi endpoint configuration.
    @BeforeAll
    static void setup() throws Exception {
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
        server.createContext("/api/v1/logins", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                if ("Bearer private-poll".equals(exchange.getRequestHeaders().getFirst("Authorization")))
                    cancelled.incrementAndGet();
                exchange.sendResponseHeaders(204, -1);
            } else if ("GET".equals(exchange.getRequestMethod())) {
                byte[] bytes = "{\"status\":\"PENDING\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else {
                creationAttempts.incrementAndGet();
                if (creationFailures.getAndUpdate(value -> Math.max(0, value - 1)) > 0) {
                    exchange.sendResponseHeaders(503, -1);
                    exchange.close();
                    return;
                }
                entered.countDown();
                try { release.await(5, TimeUnit.SECONDS); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
                byte[] bytes = ("{\"request_id\":\"application\",\"poll_token\":\"private-poll\","
                        + "\"code\":\"ABCD-EFGH-JKLM\",\"group_label\":\"测试群\",\"expires_at\":"
                        + (Instant.now().getEpochSecond() + codeLifetime) + "}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(201, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
            exchange.close();
        });
        server.createContext("/api/yggdrasil/", exchange -> {
            byte[] bytes = "{\"meta\":{\"serverName\":\"Test\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.createContext("/api/yggdrasil/authserver/validate", exchange -> {
            legacyValidations.incrementAndGet();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String token = JsonParser.parseString(body).getAsJsonObject().get("accessToken").getAsString();
            if (token.equals("game") || token.equals("renewed-game")) exchange.sendResponseHeaders(204, -1);
            else {
                byte[] error = "{\"error\":\"ForbiddenOperationException\",\"errorMessage\":\"expired\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(403, error.length);
                exchange.getResponseBody().write(error);
            }
            exchange.close();
        });
        server.createContext("/api/v1/game/profile", exchange -> {
            stallSession("profile");
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            if ("Bearer game".equals(token) || "Bearer renewed-game".equals(token)) {
                byte[] bytes = ("{\"id\":\"12345678901234567890123456789012\",\"name\":\"" + profileName + "\"}").getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders(403, -1);
            exchange.close();
        });
        server.createContext("/api/v1/sessions/refresh", exchange -> {
            String token = exchange.getRequestHeaders().getFirst("Authorization");
            if ("DELETE".equals(exchange.getRequestMethod())) {
                stallSession("revoke");
                exchange.sendResponseHeaders("Bearer revoke-failure".equals(token) ? 503 : 204, -1);
            } else if ("Bearer launcher-refresh".equals(token) || "Bearer wrong-profile".equals(token) || "Bearer drop-response".equals(token)) {
                stallSession("refresh");
                refreshed.incrementAndGet();
                if ("Bearer drop-response".equals(token)) {
                    lostResponseKeys.add(Objects.requireNonNull(exchange.getRequestHeaders().getFirst("X-Request-ID")));
                    if (lostResponseKeys.size() == 1) {
                        exchange.sendResponseHeaders(200, 100);
                        exchange.getResponseBody().write('x');
                        exchange.close();
                        return;
                    }
                }
                var session = refreshSession("renewed-game", "rotated-refresh");
                if ("Bearer wrong-profile".equals(token))
                    session.getAsJsonObject("game").getAsJsonObject("selectedProfile").addProperty("id", "00000000000000000000000000000000");
                byte[] bytes = session.toString().getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            } else exchange.sendResponseHeaders("Bearer unavailable".equals(token) ? 503 : 403, -1);
            exchange.close();
        });
        server.start();
        System.setProperty("nomi.api.base", "http://127.0.0.1:" + server.getAddress().getPort());
        JavaFXLauncher.start();
        assertTrue(JavaFXLauncher.isStarted());
        fx(() -> {
            Platform.setImplicitExit(false);
            var clipboard = Clipboard.getSystemClipboard();
            for (DataFormat type : clipboard.getContentTypes()) {
                @Nullable Object value = clipboard.getContent(type);
                if (value != null) savedClipboard.put(type, value);
            }
            return true;
        });
    }

    /// Releases the local server after all tests.
    @AfterAll
    static void cleanup() throws Exception {
        Objects.requireNonNull(server).stop(0);
        fx(() -> {
            var content = new ClipboardContent();
            content.putAll(savedClipboard);
            Clipboard.getSystemClipboard().setContent(content);
            return true;
        });
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
        assertTrue(account.isPortable());
        assertTrue(NomiService.permits(account));
        assertEquals("Alice", account.getProfileName());
        var renewed = NomiService.accountFromSession(server, session, account.getAccountID());
        assertEquals(account, renewed);
        account.setPortable(false);
        assertFalse(NomiService.permits(account));
    }
    /// Executes UI actions on the JavaFX application thread with a bounded wait.
    private static <T> T fx(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        Platform.runLater(task);
        return task.get(10, TimeUnit.SECONDS);
    }

    /// Waits for an asynchronous request to reach the fake backend.
    private static void awaitCancellation(int expected) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (cancelled.get() < expected && System.nanoTime() < deadline) Thread.sleep(20);
        assertEquals(expected, cancelled.get());
    }

    /// Closing during POST creation cancels the subsequently returned application.
    @Test
    void closeDuringCreation() throws Exception {
        release = new CountDownLatch(1);
        entered = new CountDownLatch(1);
        int before = cancelled.get();
        var pane = fx(() -> { var view = new NomiAccountPane(); view.onDialogShown(); return view; });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        fx(() -> { pane.onDialogClosed(); return true; });
        release.countDown();
        awaitCancellation(before + 1);
    }

    /// A code is copied as a complete command; only the close action remains visible.
    @Test
    void codePageAndCloseCancellation() throws Exception {
        release = new CountDownLatch(0);
        entered = new CountDownLatch(1);
        int before = cancelled.get();
        var pane = fx(() -> { var view = new NomiAccountPane(); view.onDialogShown(); return view; });
        assertTrue(entered.await(10, TimeUnit.SECONDS));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(() -> pane.lookup(".code-label") != null && "登录 ABCD-EFGH-JKLM".equals(Clipboard.getSystemClipboard().getString()))
                && System.nanoTime() < deadline) Thread.sleep(20);
        fx(() -> {
            assertEquals("登录 ABCD-EFGH-JKLM", Clipboard.getSystemClipboard().getString());
            assertNotNull(pane.lookup(".code-label"));
            assertTrue(pane.lookupAll(".label").stream().anyMatch(node -> node instanceof javafx.scene.control.Label label && label.getText().startsWith("验证码剩余")));
            var root = new StackPane(pane);
            root.setStyle("-fx-background-color: #292930;");
            var scene = new Scene(root, 600, 360, Color.web("#292930"));
            @Unmodifiable List<String> stylesheets = List.of("root.css", "blue.css", "brightness-dark.css", "font.css");
            for (String css : stylesheets)
                scene.getStylesheets().add(Objects.requireNonNull(getClass().getResource("/assets/css/" + css)).toExternalForm());
            root.applyCss();
            root.layout();
            var buttons = root.lookupAll(".button").stream().filter(javafx.scene.Node::isVisible).toList();
            assertEquals(1, buttons.size());
            assertEquals("关闭", ((Button) buttons.get(0)).getText());
            var snapshot = root.snapshot(null, null);
            var output = new BufferedImage((int) snapshot.getWidth(), (int) snapshot.getHeight(), BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < output.getHeight(); y++)
                for (int x = 0; x < output.getWidth(); x++) output.setRGB(x, y, snapshot.getPixelReader().getArgb(x, y));
            Path path = Path.of("build", "nomi-code-page.png");
            Files.createDirectories(path.getParent());
            ImageIO.write(output, "png", path.toFile());
            pane.onDialogClosed();
            return true;
        });
        awaitCancellation(before + 1);
    }
    /// Slow validation, rotation and logout never hold the monitor used by UI saves; rotation preserves a concurrent rename.
    @Test
    void sessionNetworkOperationsDoNotBlockSnapshots() throws Exception {
        for (String route : List.of("profile", "refresh", "revoke")) {
            var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH),
                    refreshSession(route.equals("refresh") ? "expired-game" : "game", "launcher-refresh"));
            slowSessionRoute = route;
            sessionEntered = new CountDownLatch(1);
            sessionRelease = new CountDownLatch(1);
            var pending = java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    if (route.equals("revoke")) account.revokeSession();
                    else account.ensureGameSession();
                } catch (AuthenticationException exception) { throw new java.util.concurrent.CompletionException(exception); }
            });
            try {
                assertTrue(sessionEntered.await(5, TimeUnit.SECONDS));
                var snapshot = java.util.concurrent.CompletableFuture.supplyAsync(() -> {
                    var data = new JsonObject();
                    account.writePrivateData(data);
                    account.acceptProfileName("ConcurrentRename");
                    return data;
                });
                assertEquals(route.equals("refresh") ? "expired-game" : "game", snapshot.get(1, TimeUnit.SECONDS).get("accessToken").getAsString());
                assertFalse(pending.isDone());
            } finally {
                sessionRelease.countDown();
                pending.get(5, TimeUnit.SECONDS);
                slowSessionRoute = "";
            }
            assertEquals("ConcurrentRename", account.getProfileName());
            if (route.equals("refresh")) {
                var data = new JsonObject();
                account.writePrivateData(data);
                assertEquals("rotated-refresh", data.get(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY).getAsString());
            }
            if (route.equals("revoke")) assertThrows(CredentialExpiredException.class, account::ensureGameSession);
        }
    }

    /// Simultaneous account operations rotate a single-use credential only once.
    @Test
    void concurrentSessionChecksRotateOnce() throws Exception {
        var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH),
                refreshSession("expired-game", "launcher-refresh"));
        int before = refreshed.get();
        var tasks = new java.util.ArrayList<java.util.concurrent.CompletableFuture<Void>>();
        for (int i = 0; i < 8; i++) tasks.add(java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { account.ensureGameSession(); }
            catch (AuthenticationException exception) { throw new java.util.concurrent.CompletionException(exception); }
        }));
        java.util.concurrent.CompletableFuture.allOf(tasks.toArray(java.util.concurrent.CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
        assertEquals(before + 1, refreshed.get());
    }

    /// Account operations resume only for the originally requested profile after group verification.
    @Test
    void accountOperationsPreserveIdentity() throws Exception {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var old = NomiService.accountFromSession(server, refreshSession("old", "old-refresh"));
        for (boolean same : new boolean[] { true, false }) {
            var session = refreshSession("new", "new-refresh");
            if (!same) session.getAsJsonObject("game").getAsJsonObject("selectedProfile").addProperty("id", "00000000000000000000000000000000");
            var replacement = NomiService.accountFromSession(server, session);
            var result = NomiService.verifiedAccount(old, account -> {
                if (account == old) throw new CredentialExpiredException("expired");
                return new org.jackhuang.hmcl.auth.AuthInfo("Alice", account.getProfileID(), "new", "mojang", "{}");
            }, () -> java.util.concurrent.CompletableFuture.completedFuture(replacement));
            if (same) assertSame(replacement, result.get(5, TimeUnit.SECONDS));
            else assertInstanceOf(java.util.concurrent.CancellationException.class,
                    assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
        }
    }

    /// A rejected logout is not silently reported as success.
    @Test
    void revocationChecksHttpStatus() throws Exception {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var rejected = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("game", "revoke-failure"));
        assertThrows(AuthenticationException.class, rejected::revokeSession);
        var accepted = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("game", "refresh"));
        accepted.revokeSession();
    }

    /// A slow rename leaves the monitor available to UI credential persistence.
    @Test
    void slowRenameDoesNotHoldAccountMonitor() throws Exception {
        var account = NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH), refreshSession("game", "refresh"));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var http = Objects.requireNonNull(server);
        http.createContext("/api/v1/game/profiles/", exchange -> {
            entered.countDown();
            try { release.await(5, TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            byte[] bytes = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        var pending = java.util.concurrent.CompletableFuture.runAsync(() -> {
            try { NomiService.renameProfile(account, "Renamed"); }
            catch (Exception exception) { throw new java.util.concurrent.CompletionException(exception); }
        });
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var snapshot = java.util.concurrent.CompletableFuture.runAsync(() -> {
                synchronized (account) { account.writePrivateData(new JsonObject()); }
            });
            snapshot.get(1, TimeUnit.SECONDS);
            assertFalse(pending.isDone());
        } finally {
            release.countDown();
            pending.get(5, TimeUnit.SECONDS);
            http.removeContext("/api/v1/game/profiles/");
        }
    }

    /// Dialog closure propagates cancellation to the waiting launch future.
    @Test
    void closingLoginPaneCompletesCancellation() throws Exception {
        var pane = fx(NomiAccountPane::new);
        var result = pane.loginResult().toCompletableFuture();
        assertFalse(result.isDone());
        fx(() -> { pane.onDialogClosed(); return true; });
        assertInstanceOf(java.util.concurrent.CancellationException.class,
                assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    /// Verification replaces expired credentials before the waiting launch resumes.
    @Test
    void launchRecoversExpiredCredentials() throws Exception {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var old = NomiService.accountFromSession(server, refreshSession("old", "old-refresh"));
        var replacement = NomiService.accountFromSession(server, refreshSession("new", "new-refresh"));
        var verified = new java.util.concurrent.CompletableFuture<org.jackhuang.hmcl.auth.Account>();
        var prompted = new CountDownLatch(1);
        var selected = new java.util.concurrent.atomic.AtomicReference<@Nullable Account>();
        var expected = new org.jackhuang.hmcl.auth.AuthInfo("Alice", replacement.getProfileID(), "new", "mojang", "{}");
        var result = NomiService.logInForLaunch(old, selected::set, account -> {
            if (account == old) throw new CredentialExpiredException("expired");
            assertSame(replacement, account);
            return expected;
        }, () -> { prompted.countDown(); return verified; });
        assertTrue(prompted.await(5, TimeUnit.SECONDS));
        assertFalse(result.isDone());
        verified.complete(replacement);
        assertSame(expected, result.get(5, TimeUnit.SECONDS));
        assertSame(replacement, selected.get());
    }

    /// Network failures must not request a new login code.
    @Test
    void launchPreservesNetworkFailure() {
        var account = NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH), refreshSession("game", "refresh"));
        var failure = new AuthenticationException("network unavailable");
        var result = NomiService.logInForLaunch(account, ignored -> fail("unexpected replacement"),
                ignored -> { throw failure; }, () -> { fail("unexpected prompt"); return new java.util.concurrent.CompletableFuture<>(); });
        assertSame(failure, assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    /// Closing verification cancels the launch instead of falling back to expired credentials.
    @Test
    void launchStopsWhenVerificationCancelled() {
        var account = NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH), refreshSession("game", "refresh"));
        var result = NomiService.logInForLaunch(account, ignored -> fail("unexpected replacement"),
                ignored -> { throw new CredentialExpiredException("expired"); },
                () -> java.util.concurrent.CompletableFuture.failedFuture(new java.util.concurrent.CancellationException("closed")));
        assertInstanceOf(java.util.concurrent.CancellationException.class,
                assertThrows(java.util.concurrent.CompletionException.class, result::join).getCause());
    }

    /// Builds an isolated device session without a real account or external service.
    private static JsonObject refreshSession(String gameToken, String refreshToken) {
        var result = JsonParser.parseString("""
                {"login_name":"Alice","game":{"accessToken":"game","clientToken":"client",
                  "selectedProfile":{"id":"12345678901234567890123456789012","name":"Alice"}}}
                """).getAsJsonObject();
        result.addProperty("refresh_token", refreshToken);
        result.getAsJsonObject("game").addProperty("accessToken", gameToken);
        return result;
    }

    /// Renewed credentials survive private storage reload and never enter public metadata.
    @Test
    void rotatingRefreshSurvivesStorageAndPreservesIdentity() throws Exception {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server,
                refreshSession("expired-game", "launcher-refresh"));
        var id = account.getAccountID();
        var uuid = account.getProfileID();
        int before = refreshed.get();
        account.ensureGameSession();
        account.ensureGameSession();
        assertEquals(before + 1, refreshed.get());
        var metadata = new JsonObject();
        var privateData = new JsonObject();
        account.writeMetadata(metadata);
        account.writePrivateData(privateData);
        assertFalse(metadata.has(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY));
        assertFalse(metadata.toString().contains("rotated-refresh"));
        assertEquals("rotated-refresh", privateData.get(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY).getAsString());
        assertEquals("renewed-game", privateData.get("accessToken").getAsString());
        var restored = (NomiAuthlibInjectorAccount) org.jackhuang.hmcl.setting.Accounts
                .getAccountFactoryByAuthlibInjectorServer(server).fromStorage(metadata, privateData);
        restored.ensureGameSession();
        assertEquals(id, restored.getAccountID());
        assertEquals(uuid, restored.getProfileID());
        restored.acceptProfileName("RenamedAlice");
        var renamed = new JsonObject();
        restored.writePrivateData(renamed);
        assertEquals("rotated-refresh", renamed.get(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY).getAsString());
        assertEquals(uuid, restored.getProfileID());
        assertEquals("RenamedAlice", restored.getProfileName());
        restored.revokeSession();
    }

    /// Invalid launcher credentials request QQ login, whereas service outages remain retryable errors.
    @Test
    void refreshExpiryAndOutageAreDifferentFailures() {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var expired = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("expired-game", "invalid"));
        assertThrows(CredentialExpiredException.class, expired::ensureGameSession);
        var unavailable = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("expired-game", "unavailable"));
        var error = assertThrows(AuthenticationException.class, unavailable::ensureGameSession);
        assertFalse(error instanceof CredentialExpiredException);
    }

    /// A mismatched profile cannot replace the saved account or private launcher credential.
    @Test
    void refreshRejectsChangedUuid() {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("expired-game", "wrong-profile"));
        var uuid = account.getProfileID();
        assertThrows(AuthenticationException.class, account::ensureGameSession);
        var stored = new JsonObject();
        account.writePrivateData(stored);
        assertEquals("wrong-profile", stored.get(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY).getAsString());
        assertEquals(uuid, account.getProfileID());
    }

    /// The actual login path returns only game credentials and never embeds the launcher refresh secret.
    @Test
    void loginRefreshKeepsLauncherSecretOutOfGameArguments() throws Exception {
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var original = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("expired-game", "launcher-refresh"));
        var metadata = new JsonObject();
        var privateData = new JsonObject();
        original.writeMetadata(metadata);
        original.writePrivateData(privateData);
        var provider = new org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactProvider() {
            /// Supplies a local artifact record without downloading anything.
            public org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo getArtifactInfo() {
                return new org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo(1, "test", Path.of("agent.jar"));
            }
            /// Makes the same artifact available for immediate lookups.
            public java.util.Optional<org.jackhuang.hmcl.auth.authlibinjector.AuthlibInjectorArtifactInfo> getArtifactInfoImmediately() {
                return java.util.Optional.of(getArtifactInfo());
            }
        };
        var account = new NomiAuthlibInjectorAccount(original.getAccountID(), server, provider, "Alice",
                org.jackhuang.hmcl.auth.yggdrasil.YggdrasilSession.fromStorage(metadata, privateData), "launcher-refresh");
        int validationsBefore = legacyValidations.get();
        var auth = account.logIn();
        assertEquals(validationsBefore, legacyValidations.get());
        assertEquals("renewed-game", auth.getAccessToken());
        var args = auth.getLaunchArguments(new org.jackhuang.hmcl.game.LaunchOptions.Builder().create());
        assertFalse(args.toString().contains("rotated-refresh"));
        assertFalse(args.toString().contains("launcher-refresh"));
        assertEquals(original.getProfileID(), account.getProfileID());
    }

    /// A process restart repeats the same request key after a transport-truncated response.
    @Test
    void lostRefreshResponseCanBeRetriedAfterReload() throws Exception {
        lostResponseKeys.clear();
        var server = new AuthlibInjectorServer(NomiService.AUTH);
        var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(server, refreshSession("expired-game", "drop-response"));
        var metadata = new JsonObject();
        var data = new JsonObject();
        account.writeMetadata(metadata);
        account.writePrivateData(data);
        assertThrows(AuthenticationException.class, account::ensureGameSession);
        var restored = (NomiAuthlibInjectorAccount) org.jackhuang.hmcl.setting.Accounts
                .getAccountFactoryByAuthlibInjectorServer(server).fromStorage(metadata, data);
        restored.ensureGameSession();
        assertEquals(2, lostResponseKeys.size());
        assertEquals(lostResponseKeys.get(0), lostResponseKeys.get(1));
        assertEquals(64, lostResponseKeys.get(0).length());
    }

    /// A bot rename updates the visible name and never changes the profile UUID or refresh grant.
    @Test
    void profileLookupUpdatesBotRename() throws Exception {
        var account = (NomiAuthlibInjectorAccount) NomiService.accountFromSession(new AuthlibInjectorServer(NomiService.AUTH), refreshSession("game", "launcher-refresh"));
        var uuid = account.getProfileID();
        try {
            profileName = "BotRenamed";
            account.ensureGameSession();
            assertEquals("BotRenamed", account.getProfileName());
            assertEquals(uuid, account.getProfileID());
            var data = new JsonObject();
            account.writePrivateData(data);
            assertEquals("launcher-refresh", data.get(NomiAuthlibInjectorAccount.REFRESH_TOKEN_KEY).getAsString());
        } finally { profileName = "Alice"; }
    }

    /// Transient failures expose retry, and retry reuses the dialog instead of requiring it to close.
    @Test
    void failedCreationCanRetryInPlace() throws Exception {
        release = new CountDownLatch(0);
        creationFailures.set(1);
        var pane = fx(() -> { var view = new NomiAccountPane(); view.onDialogShown(); return view; });
        try {
            awaitButton(pane, "重试");
            int before = creationAttempts.get();
            fx(() -> { pane.getActions().stream().filter(node -> node instanceof Button button && "重试".equals(button.getText())).map(node -> (Button) node).findFirst().orElseThrow().fire(); return true; });
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (creationAttempts.get() == before && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(before + 1, creationAttempts.get());
        } finally { fx(() -> { pane.onDialogClosed(); return true; }); }
    }

    /// An expired code offers renewal while retaining the normal close action.
    @Test
    void expiredCodeCanBeRenewed() throws Exception {
        release = new CountDownLatch(0);
        codeLifetime = 1;
        var pane = fx(() -> { var view = new NomiAccountPane(); view.onDialogShown(); return view; });
        try { awaitButton(pane, "重新获取验证码"); }
        finally {
            codeLifetime = 300;
            fx(() -> { pane.onDialogClosed(); return true; });
        }
    }

    /// Waits for a visible recovery action without relying on a fixed UI timing delay.
    private static void awaitButton(NomiAccountPane pane, String text) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!fx(() -> pane.getActions().stream().anyMatch(node -> node instanceof Button button && button.isVisible() && text.equals(button.getText())))) {
            if (System.nanoTime() >= deadline) fail("Recovery action did not appear: " + text);
            Thread.sleep(20);
        }
    }

}

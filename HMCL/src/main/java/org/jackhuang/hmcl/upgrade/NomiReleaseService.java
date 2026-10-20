package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jackhuang.hmcl.task.FileDownloadTask.IntegrityCheck;
import org.jackhuang.hmcl.util.versioning.VersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/// Reads signed launcher releases exclusively from the Nomi repository.
@NotNullByDefault
public final class NomiReleaseService {
    /// Fixed repository prevents an update response from redirecting the launcher to another project's assets.
    public static final String REPOSITORY = "NomiTeam/nomi-launcher";
    /// GitHub's bounded release listing, including prereleases when requested.
    private static final URI FEED = URI.create("https://api.github.com/repos/" + REPOSITORY + "/releases?per_page=100");
    /// Network requests have bounded timeouts and never follow metadata redirects.
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NEVER).build();

    /// Utility class without instances.
    private NomiReleaseService() { }

    /// Returns the highest compatible release; an empty repository has no available update.
    public static @Nullable RemoteVersion fetch(boolean preview) throws IOException {
        try {
            var request = HttpRequest.newBuilder(FEED).timeout(Duration.ofSeconds(15))
                    .header("Accept", "application/vnd.github+json").header("X-GitHub-Api-Version", "2022-11-28")
                    .header("User-Agent", "Nomi-Launcher").GET().build();
            var response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 403 || response.statusCode() == 429)
                throw new IOException("GitHub 请求受限，请稍后重试。");
            if (response.statusCode() != 200) throw new IOException("GitHub 发布服务不可用：" + response.statusCode());
            return select(JsonParser.parseString(response.body()).getAsJsonArray(), preview);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException("更新检查已中断。", exception);
        } catch (RuntimeException exception) {
            throw new IOException("GitHub 发布信息无效。", exception);
        }
    }

    /// Selects only complete JAR assets with SHA-256 metadata and canonical HTTPS repository URLs.
    static @Nullable RemoteVersion select(JsonArray releases, boolean preview) {
        @Nullable RemoteVersion best = null;
        for (var element : releases) {
            try {
                JsonObject release = element.getAsJsonObject();
                if (release.get("draft").getAsBoolean()) continue;
                boolean prerelease = release.get("prerelease").getAsBoolean();
                if (prerelease && !preview) continue;
                String tag = release.get("tag_name").getAsString();
                if (!tag.matches("v[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:alpha|beta|rc)\\.[0-9]+)?")) continue;
                String version = tag.substring("v".length());
                if (version.contains("-") && !prerelease) continue;
                for (var item : release.getAsJsonArray("assets")) {
                    JsonObject asset = item.getAsJsonObject();
                    if (!("NomiLauncher-" + version + ".jar").equals(asset.get("name").getAsString())) continue;
                    String url = asset.get("browser_download_url").getAsString();
                    String expected = "https://github.com/" + REPOSITORY + "/releases/download/" + tag + "/NomiLauncher-" + version + ".jar";
                    if (!url.equals(expected) || asset.get("size").getAsLong() <= 0) continue;
                    String digest = asset.get("digest").getAsString();
                    if (!digest.matches("sha256:[a-fA-F0-9]{64}")) continue;
                    if (best == null || VersionNumber.compare(version, best.version()) > 0)
                        best = new RemoteVersion(prerelease ? UpdateChannel.DEVELOPMENT : UpdateChannel.STABLE,
                                version, url, RemoteVersion.Type.JAR, new IntegrityCheck("SHA-256", digest.substring(7)), prerelease, false);
                }
            } catch (RuntimeException ignored) { /* Ignore incomplete releases without trusting their executable metadata. */ }
        }
        return best;
    }

    /// Compares release numbers without treating a local development build as an upgrade target.
    static boolean newer(String current, String candidate) {
        return current.matches("[0-9]+\\.[0-9]+\\.[0-9]+(?:-(?:alpha|beta|rc)\\.[0-9]+)?")
                && VersionNumber.compare(candidate, current) > 0;
    }
}

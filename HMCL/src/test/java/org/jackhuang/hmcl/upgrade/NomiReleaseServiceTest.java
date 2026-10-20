package org.jackhuang.hmcl.upgrade;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/// Tests release filtering, trusted asset locations, integrity metadata and upgrade ordering.
@NotNullByDefault
class NomiReleaseServiceTest {
    /// Creates GitHub-shaped metadata without accessing a real repository.
    private static JsonObject release(String version, boolean preview) {
        var release = new JsonObject();
        release.addProperty("tag_name", "v" + version);
        release.addProperty("draft", false);
        release.addProperty("prerelease", preview);
        var asset = new JsonObject();
        asset.addProperty("name", "NomiLauncher-" + version + ".jar");
        asset.addProperty("browser_download_url", "https://github.com/NomiTeam/nomi-launcher/releases/download/v" + version + "/NomiLauncher-" + version + ".jar");
        asset.addProperty("size", 100);
        asset.addProperty("digest", "sha256:" + "a".repeat(64));
        var assets = new JsonArray();
        assets.add(asset);
        release.add("assets", assets);
        return release;
    }

    /// Stable users skip prereleases and drafts; order is based on versions rather than feed position.
    @Test void choosesStableOrOptedInPreview() {
        var releases = new JsonArray();
        releases.add(release("1.0.0", false));
        releases.add(release("2.0.0-rc.1", true));
        releases.add(release("1.1.0", false));
        var draft = release("3.0.0", false);
        draft.addProperty("draft", true);
        releases.add(draft);
        assertEquals("1.1.0", java.util.Objects.requireNonNull(NomiReleaseService.select(releases, false)).version());
        assertEquals("2.0.0-rc.1", java.util.Objects.requireNonNull(NomiReleaseService.select(releases, true)).version());
    }

    /// Incomplete, foreign and tampered asset metadata cannot become an executable download.
    @Test void rejectsUntrustedAssetsAndWrongTags() {
        for (String field : new String[]{"digest", "browser_download_url", "name", "size"}) {
            var item = release("1.0.0", false);
            item.getAsJsonArray("assets").get(0).getAsJsonObject().addProperty(field, "invalid");
            var items = new JsonArray(); items.add(item);
            assertNull(NomiReleaseService.select(items, true));
        }
        var item = release("1.0.0", false);
        item.addProperty("tag_name", "nomi-v1.0.0");
        var items = new JsonArray(); items.add(item);
        assertNull(NomiReleaseService.select(items, true));
        assertNull(NomiReleaseService.select(new JsonArray(), true));
    }

    /// Equal, older and unrelated development versions do not trigger automatic replacement.
    @Test void comparesVersionNumbersWithoutDowngrades() {
        assertTrue(NomiReleaseService.newer("1.0.0", "1.0.1"));
        assertTrue(NomiReleaseService.newer("1.0.0-rc.1", "1.0.0"));
        assertFalse(NomiReleaseService.newer("1.0.0", "1.0.0-rc.1"));
        assertFalse(NomiReleaseService.newer("1.0.0", "1.0.0"));
        assertFalse(NomiReleaseService.newer("2.0.0", "1.0.0"));
        assertFalse(NomiReleaseService.newer("3.17.SNAPSHOT", "1.0.0"));
    }
}

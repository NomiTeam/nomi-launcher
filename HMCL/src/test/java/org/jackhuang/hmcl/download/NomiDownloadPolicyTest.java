/*
 * Hello Minecraft! Launcher
 * Copyright (C) 2026 huangyuhui <huanghongxun2008@126.com> and contributors
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
package org.jackhuang.hmcl.download;

import org.jackhuang.hmcl.game.AssetObject;
import org.jackhuang.hmcl.game.GameComponentType;
import org.jackhuang.hmcl.setting.DownloadSource;
import org.jackhuang.hmcl.util.i18n.LocaleUtils;
import org.jackhuang.hmcl.util.versioning.GameVersionNumber;
import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/// Download sources remain available in mainland and non-mainland test JVMs.
@NotNullByDefault
class NomiDownloadPolicyTest {
    /// Verifies an explicitly requested test region before checking download policy.
    @BeforeAll
    static void verifyRegion() {
        @Nullable String mainland = System.getProperty("nomi.test.mainland");
        if (mainland != null)
            assertEquals(Boolean.parseBoolean(mainland), LocaleUtils.IS_CHINA_MAINLAND);
    }

    /// Every source retains official game, asset and loader download candidates.
    @ParameterizedTest
    @EnumSource(DownloadSource.class)
    void downloadsRemainAvailable(DownloadSource source) {
        var provider = new HMCLDownloadProvider("https://mirror.example.com");
        provider.setFileSource(source);
        provider.setVersionListSource(source);
        assertTrue(urls(provider.getGameVersionListCandidates()).stream()
                .anyMatch(url -> url.contains("mojang.com/")));
        var asset = new AssetObject("0123456789012345678901234567890123456789", 1);
        assertTrue(urls(provider.getAssetObjectCandidates(asset))
                .contains("https://resources.download.minecraft.net/" + asset.getLocation()));
        for (GameComponentType type : List.of(GameComponentType.GAME, GameComponentType.FORGE,
                GameComponentType.NEO_FORGE, GameComponentType.FABRIC, GameComponentType.QUILT))
            assertNotNull(provider.getVersionsAsync(type,
                    type == GameComponentType.GAME ? null : GameVersionNumber.asGameVersion("1.21.1"), true));
        assertFalse(provider.getFabricVersionListCandidates().loaderMetaCandidates().getCandidates().isEmpty());
        assertFalse(provider.getNeoForgeVersionListCandidates().getCandidates().isEmpty());
        String original = "https://piston-data.mojang.com/v1/objects/test/client.jar";
        assertTrue(urls(provider.getDownloadCandidates(List.of(original))).contains(original));
    }

    /// NeoForge keeps its official source and adds the CDN fallback in mirror mode.
    @ParameterizedTest
    @EnumSource(DownloadSource.class)
    void neoForgeCandidatesPreserveOfficialDownloads(DownloadSource source) {
        var provider = new HMCLDownloadProvider("https://mirror.example.com");
        provider.setFileSource(source);
        String original = "https://maven.neoforged.net/releases/net/neoforged/neoforge/21.1.1/neoforge-21.1.1-installer.jar";
        @Unmodifiable List<String> candidates = urls(provider.getDownloadCandidates(List.of(original)));
        assertTrue(candidates.contains(original));
        if (source == DownloadSource.MIRROR || LocaleUtils.IS_CHINA_MAINLAND)
            assertTrue(candidates.contains(original.replace("maven.neoforged.net", "neoforged.forgecdn.net")));
        if (source == DownloadSource.OFFICIAL)
            assertEquals(original, candidates.get(0));
    }

    /// Copies candidate URLs into an immutable list without performing network requests.
    private static @Unmodifiable List<String> urls(DownloadCandidates candidates) {
        return candidates.getCandidates().stream().map(DownloadCandidate::displayUrl).toList();
    }
}

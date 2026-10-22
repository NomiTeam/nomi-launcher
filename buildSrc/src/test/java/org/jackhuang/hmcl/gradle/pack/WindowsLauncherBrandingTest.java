package org.jackhuang.hmcl.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.*;

/// Verifies real upstream stubs retain executable code and attribution while receiving valid Nomi resources.
@NotNullByDefault
class WindowsLauncherBrandingTest {
    /// Reads the actual dependency resource, keeping the fixture aligned with shipped Windows binaries.
    private static byte[] stub() throws IOException {
        try (var input = Objects.requireNonNull(WindowsLauncherBrandingTest.class.getResourceAsStream("/assets/HMCLauncher.exe"))) {
            return input.readAllBytes();
        }
    }

    /// Uses a little-endian PE view for independent assertions on headers and fixed version fields.
    private static ByteBuffer view(byte[] bytes) { return ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); }

    /// Checks both textual metadata and Windows' numeric four-part version fields.
    @Test
    void stableAndPrereleaseMetadataMatchBuildVersion() throws IOException {
        for (String version : new String[] {"1.0.3", "2.14.512-rc.1"}) {
            byte[] branded = WindowsLauncherBranding.brand(stub(), version);
            var pe = view(branded);
            int header = pe.getInt(0x3c);
            int optional = header + 24;
            int count = Short.toUnsignedInt(pe.getShort(header + 6));
            int table = optional + Short.toUnsignedInt(pe.getShort(header + 20));
            int raw = pe.getInt(table + (count - 1) * 40 + 20);
            int fixed = raw + ((6 + ("VS_VERSION_INFO".length() + 1) * 2 + 3) & -4);
            assertEquals(0xfeef04bd, pe.getInt(fixed));
            int ms = version.startsWith("1.") ? 1 << 16 : 2 << 16 | 14;
            int ls = (version.startsWith("1.") ? 3 : 512) << 16;
            assertEquals(ms, pe.getInt(fixed + 8));
            assertEquals(ls, pe.getInt(fixed + 12));
            assertEquals(ms, pe.getInt(fixed + 16));
            assertEquals(ls, pe.getInt(fixed + 20));
            assertEquals(version.contains("-") ? 2 : 0, pe.getInt(fixed + 28));
            String resource = new String(branded, raw, branded.length - raw, StandardCharsets.UTF_16LE);
            assertTrue(resource.contains("Nomi Launcher for Windows"));
            assertTrue(resource.contains("NomiLauncher.exe"));
            assertTrue(resource.contains(version));
            assertTrue(resource.contains("Copyright (C) 2025 huangyuhui"));
            assertTrue(resource.contains("Copyright (C) 2026 NomiTeam (modifications)"));
        }
    }

    /// Resource editing must not mutate the dependency or change executable code, imports, or relocations.
    @Test
    void preservesOriginalCodeAndInputBytes() throws IOException {
        byte[] original = stub();
        byte[] snapshot = original.clone();
        byte[] branded = WindowsLauncherBranding.brand(original, "1.0.4");
        assertArrayEquals(snapshot, original);
        var before = view(original);
        var after = view(branded);
        int header = before.getInt(0x3c);
        int count = Short.toUnsignedInt(before.getShort(header + 6));
        assertEquals(count + 1, Short.toUnsignedInt(after.getShort(header + 6)));
        assertEquals(before.getInt(header + 40), after.getInt(header + 40));
        int table = header + 24 + Short.toUnsignedInt(before.getShort(header + 20));
        for (int i = 0; i < count; i++) {
            int section = table + i * 40;
            String name = new String(original, section, 8, StandardCharsets.US_ASCII);
            if (name.startsWith(".rsrc")) continue;
            int size = before.getInt(section + 16);
            int offset = before.getInt(section + 20);
            assertArrayEquals(Arrays.copyOfRange(original, offset, offset + size), Arrays.copyOfRange(branded, offset, offset + size));
        }
    }

    /// Missing attribution and invalid versions are fatal rather than silently producing misbranded binaries.
    @Test
    void refusesMissingCopyrightAndInvalidVersions() throws IOException {
        byte[] original = stub();
        byte[] key = "LegalCopyright".getBytes(StandardCharsets.UTF_16LE);
        for (int offset = 0; offset + key.length <= original.length; offset++) {
            if (Arrays.equals(original, offset, offset + key.length, key, 0, key.length)) {
                original[offset] = 'X';
                break;
            }
        }
        assertThrows(IllegalArgumentException.class, () -> WindowsLauncherBranding.brand(original, "1.0.4"));
        byte[] valid = stub();
        assertThrows(IllegalArgumentException.class, () -> WindowsLauncherBranding.brand(valid, "invalid"));
        assertThrows(IllegalArgumentException.class, () -> WindowsLauncherBranding.brand(valid, "65536.0.0"));
        assertThrows(IllegalArgumentException.class, () -> WindowsLauncherBranding.brand(new byte[0], "1.0.4"));
    }
}

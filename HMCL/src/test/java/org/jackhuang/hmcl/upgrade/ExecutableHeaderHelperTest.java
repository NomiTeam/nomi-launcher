package org.jackhuang.hmcl.upgrade;

import org.jetbrains.annotations.NotNullByDefault;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import static org.junit.jupiter.api.Assertions.*;

/// Verifies a downloaded JAR remains an EXE for Windows users and a JAR for Java users.
@NotNullByDefault
class ExecutableHeaderHelperTest {
    /// Isolated output location for file replacement tests.
    @TempDir Path directory;

    /// Repeated EXE updates add one header and preserve the exact archive payload.
    @Test void preservesExeWrapperAcrossUpdates() throws Exception {
        Path jar = directory.resolve("download.jar");
        try (var zip = new ZipOutputStream(Files.newOutputStream(jar))) {
            zip.putNextEntry(new ZipEntry("assets/HMCLauncher.exe"));
            zip.write(new byte[]{'M', 'Z', 1, 2}); zip.closeEntry();
            zip.putNextEntry(new ZipEntry("payload"));
            zip.write("Nomi".getBytes(java.nio.charset.StandardCharsets.UTF_8)); zip.closeEntry();
        }
        Path exe = directory.resolve("NomiLauncher.exe");
        ExecutableHeaderHelper.copyWithHeader(jar, exe);
        assertEquals(Files.size(jar) + 4, Files.size(exe));
        Path next = directory.resolve("updated.exe");
        ExecutableHeaderHelper.copyWithHeader(exe, next);
        assertArrayEquals(Files.readAllBytes(exe), Files.readAllBytes(next));
        try (var zip = new ZipFile(next.toFile())) { assertNotNull(zip.getEntry("payload")); }
        Path restored = directory.resolve("updated.jar");
        ExecutableHeaderHelper.copyWithHeader(next, restored);
        assertArrayEquals(Files.readAllBytes(jar), Files.readAllBytes(restored));
    }
    /// A malformed download cannot truncate or replace the existing launcher.
    @Test void failedReplacementPreservesOriginal() throws Exception {
        Path original = directory.resolve("NomiLauncher.exe");
        byte[] content = "existing launcher".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Files.write(original, content);
        Path invalid = directory.resolve("invalid.jar");
        Files.writeString(invalid, "not a ZIP archive");
        assertThrows(java.io.IOException.class, () -> ExecutableHeaderHelper.copyWithHeader(invalid, original));
        assertArrayEquals(content, Files.readAllBytes(original));
        try (var files = Files.list(directory)) {
            assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".nomi-update-")));
        }
    }

    /// Standard release names advance with the version, including prerelease filenames.
    @Test void updatesStandardNamesButKeepsCustomNames() {
        assertEquals(java.util.Optional.of(Path.of("NomiLauncher-1.0.1.exe")), UpdateHandler.tryRename(Path.of("NomiLauncher-1.0.0.exe"), "1.0.1"));
        assertEquals(java.util.Optional.of(Path.of("NomiLauncher-1.1.0.jar")), UpdateHandler.tryRename(Path.of("NomiLauncher-1.1.0-rc.1.jar"), "1.1.0"));
        assertEquals(java.util.Optional.empty(), UpdateHandler.tryRename(Path.of("MyLauncher.exe"), "1.0.1"));
    }

}

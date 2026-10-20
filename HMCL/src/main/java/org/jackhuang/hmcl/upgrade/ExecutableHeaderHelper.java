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
package org.jackhuang.hmcl.upgrade;

import java.io.IOException;
import java.io.InputStream;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileChannel.MapMode;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import org.jackhuang.hmcl.util.io.IOUtils;

import static java.nio.file.StandardOpenOption.*;
import static org.jackhuang.hmcl.util.Lang.mapOf;
import static org.jackhuang.hmcl.util.Pair.pair;

/**
 * Helper class for adding/removing executable header from HMCL file.
 *
 * @author yushijinhun
 */
final class ExecutableHeaderHelper {
    private ExecutableHeaderHelper() {}

    private static Map<String, String> suffix2header = mapOf(
            pair("exe", "assets/HMCLauncher.exe"),
            pair("sh", "assets/HMCLauncher.sh")
    );

    private static Optional<String> getSuffix(Path file) {
        String filename = file.getFileName().toString();
        int idxDot = filename.lastIndexOf('.');
        if (idxDot < 0) {
            return Optional.empty();
        } else {
            return Optional.of(filename.substring(idxDot + 1));
        }
    }

    private static Optional<byte[]> readHeader(ZipFile zip, String suffix) throws IOException {
        String location = suffix2header.get(suffix);
        if (location != null) {
            ZipEntry entry = zip.getEntry(location);
            if (entry != null && !entry.isDirectory()) {
                try (InputStream in = zip.getInputStream(entry)) {
                    return Optional.of(IOUtils.readFully(in));
                }
            }
        }
        return Optional.empty();
    }

    private static int detectHeaderLength(ZipFile zip, FileChannel channel) throws IOException {
        ByteBuffer buf = channel.map(MapMode.READ_ONLY, 0, channel.size());
        suffixLoop: for (String suffix : suffix2header.keySet()) {
            Optional<byte[]> header = readHeader(zip, suffix);
            if (header.isPresent()) {
                ((Buffer) buf).rewind();
                for (byte b : header.get()) {
                    if (!buf.hasRemaining() || b != buf.get()) {
                        continue suffixLoop;
                    }
                }
                return header.get().length;
            }
        }
        return 0;
    }

    /**
     * Copies the executable and removes its header.
     */
    public static void copyWithoutHeader(Path from, Path to) throws IOException {
        try (
                FileChannel in = FileChannel.open(from, READ);
                FileChannel out = FileChannel.open(to, CREATE, WRITE, TRUNCATE_EXISTING);
                ZipFile zip = new ZipFile(from.toFile())
        ) {
            in.transferTo(detectHeaderLength(zip, in), Long.MAX_VALUE, out);
        }
    }

    /// Prepares a complete replacement before touching the installed launcher.
    /// Windows uses upstream-compatible in-place writes because open ZIP handles can forbid renames.
    public static void copyWithHeader(Path from, Path to) throws IOException {
        Path target = to.toAbsolutePath();
        Path temporary = java.nio.file.Files.createTempFile(target.getParent(), ".nomi-update-", ".tmp");
        try {
            try (FileChannel in = FileChannel.open(from, READ);
                    FileChannel out = FileChannel.open(temporary, WRITE, TRUNCATE_EXISTING);
                    ZipFile zip = new ZipFile(from.toFile())) {
                Optional<String> suffix = getSuffix(target);
                if (suffix.isPresent()) {
                    Optional<byte[]> header = readHeader(zip, suffix.get());
                    if (header.isPresent()) {
                        ByteBuffer buffer = ByteBuffer.wrap(header.get());
                        while (buffer.hasRemaining()) out.write(buffer);
                    }
                }
                long offset = detectHeaderLength(zip, in);
                ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
                in.position(offset);
                while (in.read(buffer) != -1) {
                    buffer.flip();
                    while (buffer.hasRemaining()) out.write(buffer);
                    buffer.clear();
                }
                out.force(true);
            }
            // Preserve executable bits for existing Unix launcher scripts as well as JAR permissions.
            if (java.nio.file.Files.exists(target)
                    && java.nio.file.Files.getFileAttributeView(target, java.nio.file.attribute.PosixFileAttributeView.class) != null) {
                java.nio.file.Files.setPosixFilePermissions(temporary, java.nio.file.Files.getPosixFilePermissions(target));
            }
            if (org.jackhuang.hmcl.util.platform.OperatingSystem.CURRENT_OS
                    == org.jackhuang.hmcl.util.platform.OperatingSystem.WINDOWS && java.nio.file.Files.exists(target)) {
                replaceWindowsLauncher(temporary, target);
                return;
            }
            try {
                java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
                java.nio.file.Files.move(temporary, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            java.nio.file.Files.deleteIfExists(temporary);
        }
    }

    /// Keeps a recoverable copy while writing through the existing Windows file identity, as upstream does.
    private static void replaceWindowsLauncher(Path prepared, Path target) throws IOException {
        Path backup = java.nio.file.Files.createTempFile(target.getParent(), ".nomi-backup-", ".tmp");
        boolean retainBackup = false;
        try {
            java.nio.file.Files.copy(target, backup, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try {
                overwriteExistingFile(prepared, target);
            } catch (IOException failure) {
                try { overwriteExistingFile(backup, target); }
                catch (IOException recoveryFailure) {
                    retainBackup = true;
                    failure.addSuppressed(new IOException("Original launcher backup retained at " + backup, recoveryFailure));
                }
                throw failure;
            }
        } finally {
            if (!retainBackup) java.nio.file.Files.deleteIfExists(backup);
        }
    }

    /// Writes to an existing file without deleting or renaming the ZIP-backed file held by a JVM.
    private static void overwriteExistingFile(Path source, Path target) throws IOException {
        try (FileChannel in = FileChannel.open(source, READ);
                FileChannel out = FileChannel.open(target, WRITE)) {
            out.truncate(0);
            ByteBuffer buffer = ByteBuffer.allocate(64 * 1024);
            while (in.read(buffer) != -1) {
                buffer.flip();
                while (buffer.hasRemaining()) out.write(buffer);
                buffer.clear();
            }
            out.force(true);
        }
    }
}

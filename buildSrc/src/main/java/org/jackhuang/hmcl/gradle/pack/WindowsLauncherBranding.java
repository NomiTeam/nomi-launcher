package org.jackhuang.hmcl.gradle.pack;

import org.jetbrains.annotations.NotNullByDefault;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.Unmodifiable;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/// Rebrands PE version and icon resources without altering executable code or requiring Windows tooling.
@NotNullByDefault
public final class WindowsLauncherBranding {
    /// No instances are needed for the build-time resource transformation.
    private WindowsLauncherBranding() {}

    /// Produces a new PE with version data in an added read-only section and preserves upstream copyright text.
    public static byte[] brand(byte[] input, String version) {
        return brand(input, version, null);
    }

    /// Replaces version information and, when provided, all existing application icon resources.
    public static byte[] brand(byte[] input, String version, byte @Nullable [] iconFile) {
        byte[] original = input.clone();
        if (original.length < 64 || original[0] != 'M' || original[1] != 'Z')
            throw new IllegalArgumentException("Windows launcher is not a PE executable");
        var source = ByteBuffer.wrap(original).order(ByteOrder.LITTLE_ENDIAN);
        int pe = source.getInt(0x3c);
        if (pe < 64 || pe > original.length - 24 || source.getInt(pe) != 0x4550)
            throw new IllegalArgumentException("Invalid PE signature");
        int count = Short.toUnsignedInt(source.getShort(pe + 6));
        int optional = pe + 24;
        int table = optional + Short.toUnsignedInt(source.getShort(pe + 20));
        int directories = optional + switch (Short.toUnsignedInt(source.getShort(optional))) {
            case 0x10b -> 96;
            case 0x20b -> 112;
            default -> throw new IllegalArgumentException("Unsupported PE optional header");
        };
        int fileAlignment = source.getInt(optional + 36);
        int sectionAlignment = source.getInt(optional + 32);
        int minimumRaw = original.length;
        int virtualEnd = 0;
        var sections = new ArrayList<Section>();
        for (int i = 0; i < count; i++) {
            int offset = table + i * 40;
            var section = new Section(source.getInt(offset + 12), source.getInt(offset + 16), source.getInt(offset + 20));
            sections.add(section);
            minimumRaw = Math.min(minimumRaw, section.raw());
            virtualEnd = Math.max(virtualEnd, Math.addExact(section.address(), Math.max(section.size(), source.getInt(offset + 8))));
        }
        int addedHeader = table + count * 40;
        if (count == 0 || count >= 96 || addedHeader + 40 > minimumRaw)
            throw new IllegalArgumentException("PE has no spare section header space");
        for (int i = addedHeader; i < addedHeader + 40; i++)
            if (original[i] != 0) throw new IllegalArgumentException("PE section header padding is not empty");
        int resource = locate(sections, source.getInt(directories + 16));
        int resourceSize = source.getInt(directories + 20);
        if (resourceSize < 16 || resourceSize > original.length - resource)
            throw new IllegalArgumentException("Invalid resource directory size");
        int named = Short.toUnsignedInt(source.getShort(resource + 12));
        int ids = Short.toUnsignedInt(source.getShort(resource + 14));
        var leaves = new ArrayList<Leaf>();
        for (int i = 0; i < named + ids; i++) {
            int entry = resource + 16 + i * 8;
            if (source.getInt(entry) == 16) {
                int child = source.getInt(entry + 4);
                if (child >= 0) throw new IllegalArgumentException("Invalid VERSION resource tree");
                collect(source, resource, child & 0x7fffffff, 0, 0, leaves);
            }
        }
        if (leaves.isEmpty()) throw new IllegalArgumentException("Windows launcher has no VERSION resource");
        var resources = new ByteArrayOutputStream();
        // Clone the original directory so all existing resources and named entries remain available.
        resources.writeBytes(new byte[align(resourceSize, 4)]);
        int address = align(virtualEnd, sectionAlignment);
        for (var leaf : leaves) {
            int old = locate(sections, source.getInt(leaf.entry()));
            int oldSize = source.getInt(leaf.entry() + 4);
            String copyright = copyright(Arrays.copyOfRange(original, old, Math.addExact(old, oldSize)));
            byte[] replacement = versionInfo(version, leaf.language(), copyright);
            source.putInt(leaf.entry(), address + resources.size());
            source.putInt(leaf.entry() + 4, replacement.length);
            resources.writeBytes(replacement);
            pad(resources);
        }
        // Windows requires the moved resource directory's reachable data to reside at or after its base RVA.
        // Move manifest and other retained resources too; otherwise the PE parses but CreateProcess rejects it.
        for (int i = 0; i < named + ids; i++) {
            int entry = resource + 16 + i * 8;
            int type = source.getInt(entry);
            if (iconFile != null && (type == 3 || type == 14)) continue;
            int child = source.getInt(entry + 4);
            if (child >= 0) throw new IllegalArgumentException("Invalid resource type directory");
            var retained = new ArrayList<Leaf>();
            collect(source, resource, child & 0x7fffffff, 0, 0, retained);
            for (var leaf : retained) {
                int rva = source.getInt(leaf.entry());
                if (rva >= address) continue; // Version data has already been replaced in the new section.
                int old = locate(sections, rva);
                int size = source.getInt(leaf.entry() + 4);
                source.putInt(leaf.entry(), Math.addExact(address, resources.size()));
                resources.writeBytes(Arrays.copyOfRange(original, old, Math.addExact(old, size)));
                pad(resources);
            }
        }
        if (iconFile != null) {
            var images = readIcon(iconFile);
            var groups = resourceLeaves(source, resource, 14);
            if (groups.isEmpty()) throw new IllegalArgumentException("Windows launcher icon groups are missing");
            var icons = new ArrayList<IconResource>();
            var groupData = ByteBuffer.allocate(6 + images.size() * 14).order(ByteOrder.LITTLE_ENDIAN);
            groupData.putShort((short) 0).putShort((short) 1).putShort((short) images.size());
            int id = 1;
            int language = groups.values().iterator().next().getFirst().language();
            for (var image : images.entrySet()) {
                int size = image.getKey();
                byte[] png = image.getValue();
                icons.add(new IconResource(id, language, png));
                groupData.put((byte) size).put((byte) size).putShort((short) 0)
                        .putShort((short) 1).putShort((short) 32).putInt(png.length).putShort((short) id++);
            }
            var replacementGroups = new ArrayList<IconResource>();
            for (var group : groups.entrySet()) {
                if (group.getValue().size() != 1)
                    throw new IllegalArgumentException("Multiple icon-group languages are not supported by this stub");
                replacementGroups.add(new IconResource(group.getKey(), group.getValue().getFirst().language(), groupData.array()));
            }
            replaceIconTree(source, resource, resources, address, 3, icons);
            replaceIconTree(source, resource, resources, address, 14, replacementGroups);
        }
        int raw = align(original.length, fileAlignment);
        byte[] content = resources.toByteArray();
        System.arraycopy(original, resource, content, 0, resourceSize);
        int rawSize = align(content.length, fileAlignment);
        byte[] output = Arrays.copyOf(original, Math.addExact(raw, rawSize));
        System.arraycopy(content, 0, output, raw, content.length);
        var result = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
        result.putInt(directories + 16, address);
        result.putInt(directories + 20, content.length);
        result.putShort(pe + 6, (short) (count + 1));
        result.putInt(optional + 8, Math.addExact(source.getInt(optional + 8), rawSize));
        result.putInt(optional + 56, align(address + content.length, sectionAlignment));
        result.position(addedHeader);
        result.put(new byte[]{'.', 'n', 'o', 'm', 'i', 'v', 'e', 'r'});
        result.putInt(content.length).putInt(address).putInt(rawSize).putInt(raw);
        result.position(addedHeader + 36);
        result.putInt(0x40000040); // Initialized, readable data; never executable or discardable.
        // Editing a signed upstream binary invalidates Authenticode; never retain a misleading certificate reference.
        result.putLong(directories + 32, 0);
        result.putInt(optional + 64, 0);
        long checksum = 0;
        for (int i = 0; i < output.length; i += 2) {
            checksum += Byte.toUnsignedInt(output[i]) | (i + 1 < output.length ? Byte.toUnsignedInt(output[i + 1]) << 8 : 0);
            checksum = (checksum & 0xffff) + (checksum >>> 16);
        }
        checksum = (checksum & 0xffff) + (checksum >>> 16);
        result.putInt(optional + 64, (int) checksum + output.length);
        return output;
    }

    /// Writes a complete icon tree so every supplied ICO size is retained, including 64px and 128px.
    private static void replaceIconTree(ByteBuffer source, int base, ByteArrayOutputStream resources,
                                        int address, int type, List<IconResource> entries) {
        int offset = resources.size();
        int metadataSize = 16 + entries.size() * 48;
        var tree = ByteBuffer.allocate(metadataSize).order(ByteOrder.LITTLE_ENDIAN);
        tree.putShort(14, (short) entries.size());
        var payload = new ByteArrayOutputStream();
        for (int i = 0; i < entries.size(); i++) {
            var entry = entries.get(i);
            int directory = 16 + entries.size() * 8 + i * 40;
            int leaf = directory + 24;
            tree.putInt(16 + i * 8, entry.id());
            tree.putInt(20 + i * 8, (offset + directory) | 0x80000000);
            tree.putShort(directory + 14, (short) 1);
            tree.putInt(directory + 16, entry.language());
            tree.putInt(directory + 20, offset + leaf);
            tree.putInt(leaf, Math.addExact(address, offset + metadataSize + payload.size()));
            tree.putInt(leaf + 4, entry.bytes().length);
            payload.writeBytes(entry.bytes());
            pad(payload);
        }
        int count = Short.toUnsignedInt(source.getShort(base + 12)) + Short.toUnsignedInt(source.getShort(base + 14));
        boolean found = false;
        for (int i = 0; i < count; i++) {
            int entry = base + 16 + i * 8;
            if (source.getInt(entry) == type) {
                source.putInt(entry + 4, offset | 0x80000000);
                found = true;
                break;
            }
        }
        if (!found) throw new IllegalArgumentException("Windows launcher icon resource type is missing");
        resources.writeBytes(tree.array());
        resources.writeBytes(payload.toByteArray());
    }

    /// One numeric Windows resource name, language, and its replacement bytes.
    @NotNullByDefault
    private record IconResource(int id, int language, byte[] bytes) {}

    /// Reads PNG-backed ICO resolutions without a platform-specific image conversion dependency.
    private static Map<Integer, byte[]> readIcon(byte[] bytes) {
        var data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length < 6 || data.getShort(0) != 0 || data.getShort(2) != 1)
            throw new IllegalArgumentException("Invalid ICO header");
        int count = Short.toUnsignedInt(data.getShort(4));
        if (count == 0 || 6 + count * 16 > bytes.length) throw new IllegalArgumentException("Invalid ICO directory");
        var images = new java.util.TreeMap<Integer, byte[]>();
        for (int i = 0; i < count; i++) {
            int entry = 6 + i * 16;
            int width = Byte.toUnsignedInt(bytes[entry]);
            int height = Byte.toUnsignedInt(bytes[entry + 1]);
            int size = data.getInt(entry + 8);
            int offset = data.getInt(entry + 12);
            if (width != height || size < 8 || offset < 6 + count * 16 || offset > bytes.length - size)
                throw new IllegalArgumentException("Invalid ICO image bounds");
            byte[] png = Arrays.copyOfRange(bytes, offset, offset + size);
            if (!Arrays.equals(Arrays.copyOf(png, 8), new byte[]{(byte) 137, 80, 78, 71, 13, 10, 26, 10}))
                throw new IllegalArgumentException("ICO images must be PNG encoded");
            images.put(width == 0 ? 256 : width, png);
        }
        if (!images.keySet().containsAll(List.of(16, 24, 32, 48, 64, 128, 256)))
            throw new IllegalArgumentException("ICO must supply 16, 24, 32, 48, 64, 128 and 256px images");
        return images;
    }

    /// Resolves numeric resource names to their language-specific data entries.
    private static Map<Integer, List<Leaf>> resourceLeaves(ByteBuffer source, int base, int type) {
        var result = new LinkedHashMap<Integer, List<Leaf>>();
        int count = Short.toUnsignedInt(source.getShort(base + 12)) + Short.toUnsignedInt(source.getShort(base + 14));
        for (int i = 0; i < count; i++) {
            int entry = base + 16 + i * 8;
            if (source.getInt(entry) != type) continue;
            int child = source.getInt(entry + 4);
            if (child >= 0) throw new IllegalArgumentException("Invalid icon resource tree");
            int directory = base + (child & 0x7fffffff);
            int names = Short.toUnsignedInt(source.getShort(directory + 12)) + Short.toUnsignedInt(source.getShort(directory + 14));
            for (int n = 0; n < names; n++) {
                int nameEntry = directory + 16 + n * 8;
                int subtree = source.getInt(nameEntry + 4);
                if (subtree >= 0) throw new IllegalArgumentException("Invalid icon language directory");
                var leaves = new ArrayList<Leaf>();
                collect(source, base, subtree & 0x7fffffff, 1, 0, leaves);
                result.put(source.getInt(nameEntry), leaves);
            }
        }
        return result;
    }

    /// Locates a resource data RVA inside the original file's section map.
    private static int locate(List<Section> sections, int address) {
        for (var section : sections)
            if (address >= section.address() && address - section.address() < section.size())
                return Math.addExact(section.raw(), address - section.address());
        throw new IllegalArgumentException("Resource address is outside PE sections");
    }

    /// Walks the VERSION resource name/language directories with a strict depth bound.
    private static void collect(ByteBuffer source, int base, int offset, int depth, int language, List<Leaf> leaves) {
        if (depth > 2) throw new IllegalArgumentException("Invalid VERSION resource depth");
        int directory = base + offset;
        int count = Short.toUnsignedInt(source.getShort(directory + 12)) + Short.toUnsignedInt(source.getShort(directory + 14));
        for (int i = 0; i < count; i++) {
            int entry = directory + 16 + i * 8;
            int id = source.getInt(entry);
            int child = source.getInt(entry + 4);
            if (child < 0) collect(source, base, child & 0x7fffffff, depth + 1, id, leaves);
            else leaves.add(new Leaf(base + child, depth == 1 ? id : language));
        }
    }

    /// Retains the exact upstream copyright string rather than substituting ownership of its code.
    private static String copyright(byte[] resource) {
        byte[] key = utf16("LegalCopyright");
        for (int offset = 6; offset + key.length <= resource.length; offset += 2) {
            if (!Arrays.equals(resource, offset, offset + key.length, key, 0, key.length)) continue;
            int value = align(offset + key.length, 4);
            int end = value;
            while (end + 1 < resource.length && (resource[end] != 0 || resource[end + 1] != 0)) end += 2;
            String notice = new String(resource, value, end - value, StandardCharsets.UTF_16LE);
            if (!notice.isBlank()) return notice;
        }
        throw new IllegalArgumentException("Windows launcher copyright is missing; refusing to discard attribution");
    }

    /// Builds standard fixed and translated version data for the selected Nomi build.
    private static byte[] versionInfo(String version, int language, String copyright) {
        if (version.isBlank() || version.length() > 128) throw new IllegalArgumentException("Invalid Nomi version");
        var matcher = Pattern.compile("^(\\d+)(?:\\.(\\d+))?(?:\\.(\\d+))?").matcher(version);
        if (!matcher.find()) throw new IllegalArgumentException("Invalid numeric Nomi version");
        int[] numbers = new int[3];
        for (int i = 0; i < 3; i++) {
            @Nullable String component = matcher.group(i + 1);
            numbers[i] = component == null ? 0 : Integer.parseInt(component);
            if (numbers[i] > 65535) throw new IllegalArgumentException("Windows version component exceeds 65535");
        }
        int ms = numbers[0] << 16 | numbers[1];
        int ls = numbers[2] << 16;
        byte[] fixed = ByteBuffer.allocate(52).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(0xfeef04bd).putInt(0x10000).putInt(ms).putInt(ls).putInt(ms).putInt(ls)
                .putInt(0x3f).putInt(version.matches("[0-9]+\\.[0-9]+\\.[0-9]+") ? 0 : 2)
                .putInt(0x40004).putInt(1).putInt(0).putInt(0).putInt(0).array();
        var strings = new LinkedHashMap<String, String>();
        strings.put("CompanyName", "NomiTeam");
        strings.put("FileDescription", "Nomi Launcher for Windows");
        strings.put("FileVersion", version);
        strings.put("InternalName", "NomiLauncher");
        strings.put("LegalCopyright", copyright + "; Copyright (C) 2026 NomiTeam (modifications)");
        strings.put("OriginalFilename", "NomiLauncher.exe");
        strings.put("ProductName", "Nomi Launcher");
        strings.put("ProductVersion", version);
        var entries = new ArrayList<byte[]>();
        strings.forEach((key, value) -> entries.add(block(key, utf16(value), value.length() + 1, 1, List.of())));
        byte[] table = block(String.format(Locale.ROOT, "%04x04b0", language), new byte[0], 0, 1, List.copyOf(entries));
        byte[] stringInfo = block("StringFileInfo", new byte[0], 0, 1, List.of(table));
        byte[] translation = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putShort((short) language).putShort((short) 1200).array();
        byte[] var = block("Translation", translation, translation.length, 0, List.of());
        byte[] varInfo = block("VarFileInfo", new byte[0], 0, 1, List.of(var));
        return block("VS_VERSION_INFO", fixed, fixed.length, 0, List.of(stringInfo, varInfo));
    }

    /// Encodes a length-prefixed, DWORD-aligned VERSIONINFO node.
    private static byte[] block(String key, byte[] value, int valueLength, int type, @Unmodifiable List<byte[]> children) {
        var output = new ByteArrayOutputStream();
        output.writeBytes(new byte[6]);
        output.writeBytes(utf16(key));
        pad(output);
        output.writeBytes(value);
        for (byte[] child : children) { pad(output); output.writeBytes(child); }
        byte[] result = output.toByteArray();
        if (result.length > 65535) throw new IllegalArgumentException("VERSIONINFO is too large");
        ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).putShort((short) result.length).putShort((short) valueLength).putShort((short) type);
        return result;
    }

    /// Encodes a NUL-terminated Windows UTF-16 string.
    private static byte[] utf16(String value) { return (value + '\0').getBytes(StandardCharsets.UTF_16LE); }

    /// Pads a resource node to its required DWORD boundary.
    private static void pad(ByteArrayOutputStream output) { while (output.size() % 4 != 0) output.write(0); }

    /// Rounds sizes without accepting invalid PE alignments or overflowing integer offsets.
    private static int align(int value, int alignment) {
        if (alignment <= 0 || Integer.bitCount(alignment) != 1) throw new IllegalArgumentException("Invalid PE alignment");
        return Math.addExact(value, alignment - 1) & -alignment;
    }

    /// Original section coordinates needed to resolve resource RVAs.
    @NotNullByDefault
    private record Section(int address, int size, int raw) {}

    /// Resource data entry and its original Windows language identifier.
    @NotNullByDefault
    private record Leaf(int entry, int language) {}
}

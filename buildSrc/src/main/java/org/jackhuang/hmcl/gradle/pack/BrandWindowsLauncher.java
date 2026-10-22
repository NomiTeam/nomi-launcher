package org.jackhuang.hmcl.gradle.pack;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.OutputFile;
import org.gradle.api.tasks.TaskAction;
import org.jetbrains.annotations.NotNullByDefault;

import java.io.IOException;
import java.nio.file.Files;
import java.util.zip.ZipFile;

/// Generates the branded launcher resource before the containing JAR is signed.
@NotNullByDefault
public abstract class BrandWindowsLauncher extends DefaultTask {
    /// Source stub, either the upstream dependency archive or an explicitly provided executable.
    @InputFile
    public abstract RegularFileProperty getSourceFile();

    /// Multi-resolution Windows icon embedded alongside the version resources.
    @InputFile
    public abstract RegularFileProperty getIconFile();

    /// Nomi version written into both textual and numeric Windows version fields.
    @Input
    public abstract Property<String> getVersion();

    /// Branded PE embedded in the JAR and prepended to Windows executable distributions.
    @OutputFile
    public abstract RegularFileProperty getOutputFile();

    /// Reads the original executable without changing cached dependencies and writes a generated resource.
    @TaskAction
    public void generate() throws IOException {
        var source = getSourceFile().get().getAsFile().toPath();
        byte[] stub;
        if (source.getFileName().toString().endsWith(".jar")) {
            try (var zip = new ZipFile(source.toFile())) {
                var entry = zip.getEntry("assets/HMCLauncher.exe");
                if (entry == null) throw new IOException("Windows launcher resource is missing from dependency");
                try (var input = zip.getInputStream(entry)) { stub = input.readAllBytes(); }
            }
        } else stub = Files.readAllBytes(source);
        var output = getOutputFile().get().getAsFile().toPath();
        Files.createDirectories(output.getParent());
        Files.write(output, WindowsLauncherBranding.brand(stub, getVersion().get(), Files.readAllBytes(getIconFile().get().getAsFile().toPath())));
    }
}

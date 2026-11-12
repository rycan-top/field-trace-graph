package io.fieldtracegraph.javaanalysis.project;

import io.fieldtracegraph.core.support.Ids;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Stream;

// 作用：校验并规范化 classpath 条目。
// 使用场景：处理相对路径、分隔符、Windows 盘符与不存在的条目。

/**
 * Approves classpath entries against path, symlink, size and hash rules. It
 * never downloads and never follows a JAR Manifest Class-Path chain.
 */
public final class ClasspathEntryValidator {
    private static final long MAX_ENTRY_BYTES = 256L * 1024 * 1024;
    private static final long MAX_DIRECTORY_BYTES = 1024L * 1024 * 1024;
    private static final int MAX_DIRECTORY_FILES = 50_000;

    private final ClasspathManifestReader manifestReader = new ClasspathManifestReader();

    /**
     * Explicit entries are a command-line input, not a discovered convention.
     * A directory is accepted because Maven/Gradle class output and extracted
     * dependency roots are directories; manifest entries stay archive-only.
     */
    // 作用：校验清单与显式 classpath 条目；逻辑：拒绝符号链接和超限文件，核对清单哈希，并为目录或文件生成指纹。
    public ValidatedClasspath validate(
            Path projectRoot,
            ClasspathManifest manifest,
            List<String> explicitEntries
    ) {
        List<Path> paths = new ArrayList<>();
        List<String> fingerprints = new ArrayList<>();
        for (ClasspathEntry entry : manifest.entries()) {
            Path resolved = resolve(projectRoot, entry.path());
            if (Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("Classpath entry is a symbolic link: " + entry.path());
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("Classpath entry is not a regular file: " + entry.path());
            }
            long size = size(resolved);
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Classpath entry exceeds the size limit: " + entry.path());
            }
            String hash = sha256(resolved);
            if (entry.sha256() != null && !entry.sha256().equals(hash)) {
                throw new IllegalArgumentException("Classpath entry hash does not match manifest: " + entry.path());
            }
            paths.add(resolved);
            fingerprints.add(resolved.toAbsolutePath().normalize() + "\u001f" + hash);
        }
        for (String entry : explicitEntries == null ? List.<String>of() : explicitEntries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            Path resolved = resolve(projectRoot, entry);
            if (Files.isSymbolicLink(resolved)) {
                throw new IllegalArgumentException("Classpath entry is a symbolic link: " + entry);
            }
            if (Files.isDirectory(resolved)) {
                fingerprints.add(directoryFingerprint(resolved));
                paths.add(resolved);
                continue;
            }
            if (!Files.isRegularFile(resolved)) {
                throw new IllegalArgumentException("Classpath entry is not a regular file or directory: " + entry);
            }
            long size = size(resolved);
            if (size > MAX_ENTRY_BYTES) {
                throw new IllegalArgumentException("Classpath entry exceeds the size limit: " + entry);
            }
            paths.add(resolved);
            fingerprints.add(resolved.toAbsolutePath().normalize() + "\u001f" + sha256(resolved));
        }
        return new ValidatedClasspath(List.copyOf(paths), Ids.sha256(fingerprints.toArray(String[]::new)));
    }

    // 作用：计算含显式条目的 classpath 指纹；逻辑：读取默认清单并与命令行条目一起校验后返回指纹。
    public String fingerprint(Path projectRoot, List<String> explicitEntries) {
        return validate(projectRoot, manifestReader.read(projectRoot), explicitEntries).fingerprint();
    }

    // 作用：解析 classpath 条目路径；逻辑：绝对路径直接使用，相对路径拼到项目根后规范化。
    private Path resolve(Path projectRoot, String entry) {
        Path candidate = Path.of(entry);
        return (candidate.isAbsolute() ? candidate : projectRoot.resolve(candidate)).normalize();
    }

    // 作用：读取条目大小；逻辑：调用文件系统大小接口，失败时转换为参数异常。
    private long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to read classpath entry size: " + path, e);
        }
    }

    // 作用：计算文件 SHA-256；逻辑：分块读取文件并输出小写十六进制摘要。
    private String sha256(Path path) {
        try (InputStream input = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (IOException | NoSuchAlgorithmException e) {
            throw new IllegalArgumentException("Unable to hash classpath entry: " + path, e);
        }
    }

    // 作用：计算目录内容指纹；逻辑：排序遍历所有文件，累计大小并混入相对路径与各文件摘要。
    private String directoryFingerprint(Path directory) {
        List<Path> files;
        try (Stream<Path> walk = Files.walk(directory)) {
            files = walk.filter(Files::isRegularFile)
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new IllegalArgumentException("Unable to inspect classpath directory: " + directory, e);
        }
        if (files.size() > MAX_DIRECTORY_FILES) {
            throw new IllegalArgumentException(
                    "Classpath directory contains too many files: " + directory
            );
        }
        long totalBytes = 0;
        List<String> parts = new ArrayList<>(files.size() * 2 + 1);
        parts.add(directory.toAbsolutePath().normalize().toString());
        for (Path file : files) {
            totalBytes += size(file);
            if (totalBytes > MAX_DIRECTORY_BYTES) {
                throw new IllegalArgumentException(
                        "Classpath directory exceeds the size limit: " + directory
                );
            }
            parts.add(directory.relativize(file).toString().replace('\\', '/'));
            parts.add(sha256(file));
        }
        return Ids.sha256(parts.toArray(String[]::new));
    }
}

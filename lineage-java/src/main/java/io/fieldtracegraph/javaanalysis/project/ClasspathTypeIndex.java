package io.fieldtracegraph.javaanalysis.project;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.stream.Stream;

// 作用：从 JAR 或 class 目录建立类型索引。
// 使用场景：解析外部类型的成员、继承关系与重载候选。

/**
 * Read-only index of class-file structure for explicitly supplied classpath
 * entries. It is bounded and skips malformed entries instead of failing the
 * whole indexing run.
 */
public final class ClasspathTypeIndex {
    private static final int MAX_CLASSES = 60_000;
    private static final Set<String> PLATFORM_PREFIXES = Set.of(
            "java.", "javax.", "jdk.", "sun.", "com.sun."
    );

    private final Map<String, ClassFileMetadata> types;
    private final boolean truncated;

    // 作用：创建类型索引快照；逻辑：复制类型映射并记录是否因上限被截断。
    private ClasspathTypeIndex(Map<String, ClassFileMetadata> types, boolean truncated) {
        this.types = Map.copyOf(types);
        this.truncated = truncated;
    }

    // 作用：创建空类型索引；逻辑：返回无类型且未截断的实例。
    public static ClasspathTypeIndex empty() {
        return new ClasspathTypeIndex(Map.of(), false);
    }

    // 作用：从 classpath 条目加载类型索引；逻辑：逐个加载目录或归档，达到类数量上限后标记截断并停止。
    public static ClasspathTypeIndex load(List<Path> entries) {
        if (entries == null || entries.isEmpty()) {
            return empty();
        }
        Map<String, ClassFileMetadata> types = new LinkedHashMap<>();
        boolean truncated = false;
        ClassFileReader reader = new ClassFileReader();
        for (Path entry : entries) {
            if (types.size() >= MAX_CLASSES) {
                truncated = true;
                break;
            }
            if (Files.isDirectory(entry)) {
                truncated |= loadDirectory(entry, reader, types);
            } else if (Files.isRegularFile(entry)) {
                truncated |= loadArchive(entry, reader, types);
            }
        }
        return new ClasspathTypeIndex(types, truncated);
    }

    // 作用：加载目录中的 class 文件；逻辑：排序遍历并解析每个 class，损坏文件跳过，超过上限时返回截断标记。
    private static boolean loadDirectory(
            Path root,
            ClassFileReader reader,
            Map<String, ClassFileMetadata> types
    ) {
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".class"))
                    .sorted()
                    .toList()) {
                if (types.size() >= MAX_CLASSES) {
                    return true;
                }
                try (InputStream input = Files.newInputStream(file)) {
                    add(reader.read(input), types);
                } catch (IOException ignored) {
                    // Malformed class files are not trusted evidence.
                }
            }
        } catch (IOException ignored) {
            // A missing or unreadable directory contributes no metadata.
        }
        return false;
    }

    // 作用：加载 JAR 或 ZIP 中的 class 文件；逻辑：遍历归档条目并解析，跳过目录、损坏项与重复版本，超限即标记截断。
    private static boolean loadArchive(
            Path archive,
            ClassFileReader reader,
            Map<String, ClassFileMetadata> types
    ) {
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            var entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
                    continue;
                }
                if (types.size() >= MAX_CLASSES) {
                    return true;
                }
                try (InputStream input = zip.getInputStream(entry)) {
                    add(reader.read(input), types);
                } catch (IOException ignored) {
                    // Skip multi-release duplicates and malformed entries.
                }
            }
        } catch (IOException ignored) {
            // An unreadable archive contributes no metadata.
        }
        return false;
    }

    // 作用：登记解析出的类型；逻辑：忽略空元数据，并以首次出现的类型为准避免重复覆盖。
    private static void add(ClassFileMetadata metadata, Map<String, ClassFileMetadata> types) {
        if (metadata == null || metadata.name() == null || metadata.name().isBlank()) {
            return;
        }
        types.putIfAbsent(metadata.name(), metadata);
    }

    // 作用：按全限定名查询类型；逻辑：空名返回空，否则从索引映射中取值。
    public ClassFileMetadata type(String qname) {
        return qname == null ? null : types.get(qname);
    }

    // 作用：判断类型是否已索引；逻辑：查询结果非空即为存在。
    public boolean hasType(String qname) {
        return type(qname) != null;
    }

    // 作用：判断索引类型是否声明指定方法；逻辑：先取类型元数据，再委托其方法匹配逻辑。
    public boolean hasMethod(String qname, String methodName, int argumentCount) {
        ClassFileMetadata metadata = type(qname);
        return metadata != null && metadata.hasMethod(methodName, argumentCount);
    }

    // 作用：判断索引类型是否带指定注解；逻辑：先取类型元数据，再委托其注解匹配逻辑。
    public boolean hasAnnotation(String qname, String simpleNameOrDescriptor) {
        ClassFileMetadata metadata = type(qname);
        return metadata != null && metadata.hasAnnotation(simpleNameOrDescriptor);
    }

    // 作用：查询索引是否被截断；逻辑：返回加载阶段记录的截断标记。
    public boolean truncated() {
        return truncated;
    }

    // 作用：返回已索引类型数量；逻辑：读取类型映射大小。
    public int size() {
        return types.size();
    }

    // 作用：判断是否为 JDK 平台类型；逻辑：检查全限定名是否以 java、javax、jdk 等前缀开头。
    public static boolean isPlatformType(String qname) {
        if (qname == null) {
            return false;
        }
        return PLATFORM_PREFIXES.stream().anyMatch(qname::startsWith);
    }
}

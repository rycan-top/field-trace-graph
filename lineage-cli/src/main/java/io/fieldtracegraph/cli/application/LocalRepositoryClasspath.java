package io.fieldtracegraph.cli.application;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Best-effort classpath recovery from the local Maven repository.
 *
 * <p>When a private dependency cannot be resolved, Maven writes nothing at all
 * for that module, even though most of its public dependencies (Spring, Apache
 * HTTP, Jackson, ...) are already downloaded. This class reads the declared
 * dependency coordinates, applies the parent chain's properties and
 * {@code dependencyManagement} versions, and keeps every jar that exists in
 * {@code ~/.m2/repository}. It never fabricates coordinates: unresolved
 * versions are skipped.</p>
 */
// 作用：从本地 Maven 仓库恢复 classpath。
// 使用场景：私有依赖缺失导致 Maven 无法产出 classpath 时，补齐已下载的公共依赖。
public final class LocalRepositoryClasspath {
    private static final int MAX_ENTRIES_DEFAULT = 400;
    private static final int MAX_PROPERTY_DEPTH = 6;

    private final Path repository;

    // 作用：创建恢复器；逻辑：优先使用 M2_REPO，否则使用默认 ~/.m2/repository。
    public LocalRepositoryClasspath() {
        String configured = System.getenv("M2_REPO");
        this.repository = configured == null || configured.isBlank()
                ? Path.of(System.getProperty("user.home"), ".m2", "repository")
                : Path.of(configured);
    }

    // 作用：收集本地 jar；逻辑：解析所有 POM 的有效属性与依赖管理后逐条定位仓库文件。
    public Set<String> entries(Path root, List<Path> poms, int maxEntries) {
        Set<String> entries = new LinkedHashSet<>();
        if (!Files.isDirectory(repository)) {
            return entries;
        }
        int limit = maxEntries <= 0 ? MAX_ENTRIES_DEFAULT : maxEntries;
        Map<Path, PomModel> models = new LinkedHashMap<>();
        for (Path pom : poms) {
            PomModel model = read(pom);
            if (model != null) {
                models.put(pom, model);
            }
        }
        for (PomModel model : models.values()) {
            List<PomModel> chain = chain(model, models);
            Map<String, String> properties = new LinkedHashMap<>();
            Map<String, String> managed = new LinkedHashMap<>();
            for (PomModel inherited : chain) {
                properties.putAll(inherited.properties());
            }
            for (PomModel inherited : chain) {
                managed.putAll(inherited.managedVersions(properties));
            }
            for (Dependency dependency : model.dependencies()) {
                if ("test".equals(dependency.scope())) {
                    continue;
                }
                String groupId = resolve(dependency.groupId(), properties, model);
                String artifactId = resolve(dependency.artifactId(), properties, model);
                String version = resolve(dependency.version(), properties, model);
                if (version == null) {
                    version = managed.get(groupId + ":" + artifactId);
                }
                if (groupId == null || artifactId == null || version == null) {
                    continue;
                }
                Path jar = repository
                        .resolve(groupId.replace('.', '/'))
                        .resolve(artifactId)
                        .resolve(version)
                        .resolve(artifactId + "-" + version + ".jar");
                if (Files.isRegularFile(jar)) {
                    entries.add(jar.toString());
                    if (entries.size() >= limit) {
                        return entries;
                    }
                }
            }
        }
        return entries;
    }

    // 作用：构建父链；逻辑：按 relativePath 递归向上，找不到父 POM 时停止。
    private List<PomModel> chain(PomModel model, Map<Path, PomModel> models) {
        List<PomModel> chain = new ArrayList<>();
        PomModel current = model;
        Set<Path> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current.path())) {
            chain.add(0, current);
            current = current.parentPath() == null ? null : models.get(current.parentPath());
        }
        return chain;
    }

    // 作用：解析属性占位符；逻辑：反复替换 ${...}，内置 project/parent 版本，无法完全解析时返回 null。
    private String resolve(String value, Map<String, String> properties, PomModel model) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String current = value.trim();
        for (int depth = 0; depth < MAX_PROPERTY_DEPTH; depth++) {
            int start = current.indexOf("${");
            if (start < 0) {
                return current;
            }
            int end = current.indexOf('}', start);
            if (end < 0) {
                return null;
            }
            String key = current.substring(start + 2, end);
            String replacement = builtin(key, model);
            if (replacement == null) {
                replacement = properties.get(key);
            }
            if (replacement == null) {
                return null;
            }
            current = current.substring(0, start) + replacement + current.substring(end + 1);
        }
        return current.contains("${") ? null : current;
    }

    // 作用：提供 Maven 内置属性；逻辑：映射 project/pom 的 groupId、artifactId 与版本。
    private String builtin(String key, PomModel model) {
        return switch (key) {
            case "project.groupId", "pom.groupId" -> model.groupId();
            case "project.artifactId", "pom.artifactId" -> model.artifactId();
            case "project.version", "pom.version" -> model.version();
            case "parent.version" -> model.parentVersion();
            case "parent.groupId" -> model.parentGroupId();
            default -> null;
        };
    }

    // 作用：读取单个 POM；逻辑：解析项目坐标、父坐标、属性、依赖管理与直接依赖，失败时返回 null。
    private PomModel read(Path pom) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder().parse(pom.toFile());
            Element project = document.getDocumentElement();
            Element parent = directChild(project, "parent");
            String parentGroupId = text(parent, "groupId");
            String parentVersion = text(parent, "version");
            String groupId = text(project, "groupId");
            if (groupId == null) {
                groupId = parentGroupId;
            }
            String version = text(project, "version");
            if (version == null) {
                version = parentVersion;
            }
            String relativePath = parent == null ? null : text(parent, "relativePath");
            Path parentPath = null;
            if (parent != null) {
                parentPath = relativePath == null || relativePath.isBlank()
                        ? pom.getParent().getParent().resolve("pom.xml")
                        : pom.getParent().resolve(relativePath).normalize();
            }
            Map<String, String> properties = new LinkedHashMap<>();
            Element propertiesElement = directChild(project, "properties");
            if (propertiesElement != null) {
                NodeList children = propertiesElement.getChildNodes();
                for (int index = 0; index < children.getLength(); index++) {
                    Node node = children.item(index);
                    if (node instanceof Element element) {
                        String key = element.getTagName();
                        String value = element.getTextContent();
                        if (value != null && !value.isBlank()) {
                            properties.put(key, value.trim());
                        }
                    }
                }
            }
            List<Dependency> managed = dependencies(directChild(
                    directChild(project, "dependencyManagement"), "dependencies"));
            List<Dependency> direct = dependencies(directChild(project, "dependencies"));
            return new PomModel(
                    pom,
                    parentPath,
                    groupId,
                    text(project, "artifactId"),
                    version,
                    parentGroupId,
                    parentVersion,
                    properties,
                    managed,
                    direct
            );
        } catch (Exception ignored) {
            return null;
        }
    }

    // 作用：读取依赖列表；逻辑：只接受 dependencies 的直接 dependency 子元素。
    private List<Dependency> dependencies(Element dependencies) {
        List<Dependency> result = new ArrayList<>();
        if (dependencies == null) {
            return result;
        }
        NodeList children = dependencies.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && "dependency".equals(element.getTagName())) {
                result.add(new Dependency(
                        text(element, "groupId"),
                        text(element, "artifactId"),
                        text(element, "version"),
                        text(element, "scope")
                ));
            }
        }
        return result;
    }

    // 作用：读取直接子元素；逻辑：按标签名匹配，跳过空白文本节点。
    private Element directChild(Element parent, String name) {
        if (parent == null) {
            return null;
        }
        NodeList children = parent.getChildNodes();
        for (int index = 0; index < children.getLength(); index++) {
            Node node = children.item(index);
            if (node instanceof Element element && name.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    // 作用：读取子元素文本；逻辑：元素缺失或为空时返回 null。
    private String text(Element parent, String name) {
        Element child = directChild(parent, name);
        if (child == null) {
            return null;
        }
        String value = child.getTextContent();
        return value == null || value.isBlank() ? null : value.trim();
    }

    private record Dependency(String groupId, String artifactId, String version, String scope) {
    }

    private record PomModel(
            Path path,
            Path parentPath,
            String groupId,
            String artifactId,
            String version,
            String parentGroupId,
            String parentVersion,
            Map<String, String> properties,
            List<Dependency> managed,
            List<Dependency> dependencies
    ) {
        // 作用：计算依赖管理的有效版本；逻辑：把 groupId:artifactId 映射到解析后的版本。
        Map<String, String> managedVersions(Map<String, String> properties) {
            Map<String, String> result = new LinkedHashMap<>();
            for (Dependency dependency : managed) {
                if (dependency.version() == null) {
                    continue;
                }
                String group = resolveStatic(dependency.groupId(), properties, this);
                String artifact = resolveStatic(dependency.artifactId(), properties, this);
                String version = resolveStatic(dependency.version(), properties, this);
                if (group != null && artifact != null && version != null) {
                    result.put(group + ":" + artifact, version);
                }
            }
            return result;
        }

        // 作用：在记录内解析占位符；逻辑：复用内置属性规则，仅处理可完全解析的值。
        private static String resolveStatic(
                String value,
                Map<String, String> properties,
                PomModel model
        ) {
            if (value == null || value.isBlank()) {
                return null;
            }
            String current = value.trim();
            for (int depth = 0; depth < MAX_PROPERTY_DEPTH; depth++) {
                int start = current.indexOf("${");
                if (start < 0) {
                    return current;
                }
                int end = current.indexOf('}', start);
                if (end < 0) {
                    return null;
                }
                String key = current.substring(start + 2, end);
                String replacement = switch (key) {
                    case "project.groupId", "pom.groupId" -> model.groupId();
                    case "project.artifactId", "pom.artifactId" -> model.artifactId();
                    case "project.version", "pom.version" -> model.version();
                    case "parent.version" -> model.parentVersion();
                    default -> properties.get(key);
                };
                if (replacement == null) {
                    return null;
                }
                current = current.substring(0, start) + replacement + current.substring(end + 1);
            }
            return current.contains("${") ? null : current;
        }
    }
}

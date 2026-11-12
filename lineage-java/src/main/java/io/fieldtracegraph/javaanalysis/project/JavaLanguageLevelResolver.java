package io.fieldtracegraph.javaanalysis.project;

import io.fieldtracegraph.javaanalysis.io.SafeXmlReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// 作用：推断项目使用的 Java 语言级别。
// 使用场景：多模块项目存在不同 Java 版本时确定解析规则。

public final class JavaLanguageLevelResolver {
    private static final String DEFAULT_LEVEL = "17";
    private static final Pattern MAVEN_PROPERTY = Pattern.compile("\\$\\{([^}]+)}");
    private static final Pattern GRADLE_COMPATIBILITY =
            Pattern.compile("(?:sourceCompatibility|targetCompatibility)\\s*=\\s*['\"]([^'\"]+)['\"]");
    private static final Pattern GRADLE_RELEASE =
            Pattern.compile("options\\.release\\s*=\\s*(\\d+)");

    // 作用：推断模块语言级别；逻辑：不传项目根与覆盖值时，复用模块目录作为根并转交完整重载。
    public LanguageLevelResolution resolve(Path moduleDirectory) {
        return resolve(moduleDirectory, null);
    }

    // 作用：在指定模块目录推断语言级别；逻辑：以模块目录同时作为项目根，并携带命令行覆盖值。
    public LanguageLevelResolution resolve(Path moduleDirectory, String override) {
        return resolve(moduleDirectory, moduleDirectory, override);
    }

    // 作用：按覆盖值、构建文件或默认值确定语言级别；逻辑：优先命令行，再从模块向上查找构建文件，最后回退到默认版本。
    public LanguageLevelResolution resolve(Path projectRoot, Path moduleDirectory, String override) {
        if (override != null && !override.isBlank()) {
            return new LanguageLevelResolution(normalize(override), false, "cli");
        }
        Path normalizedRoot = projectRoot.toAbsolutePath().normalize();
        Path current = moduleDirectory.toAbsolutePath().normalize();
        while (current != null && current.startsWith(normalizedRoot)) {
            LanguageLevelResolution resolution = resolveBuildFile(current);
            if (resolution != null) {
                return resolution;
            }
            if (current.equals(normalizedRoot)) {
                break;
            }
            current = current.getParent();
        }
        return new LanguageLevelResolution(DEFAULT_LEVEL, true, "ASSUMED");
    }

    // 作用：读取目录中的构建文件；逻辑：依次尝试 pom.xml、build.gradle 与 build.gradle.kts，找不到返回空。
    private LanguageLevelResolution resolveBuildFile(Path directory) {
        Path pom = directory.resolve("pom.xml");
        if (Files.exists(pom)) {
            return resolvePom(pom);
        }
        Path gradle = directory.resolve("build.gradle");
        if (Files.exists(gradle)) {
            return resolveGradle(gradle);
        }
        Path gradleKts = directory.resolve("build.gradle.kts");
        if (Files.exists(gradleKts)) {
            return resolveGradle(gradleKts);
        }
        return null;
    }

    // 作用：从 Maven POM 推断语言级别；逻辑：按 release、target、source、java.version 顺序取第一个值并展开属性。
    private LanguageLevelResolution resolvePom(Path pom) {
        try {
            Document document = SafeXmlReader.parse(pom);
            Map<String, String> properties = pomProperties(document);
            String level = null;
            for (String tag : new String[]{
                    "release",
                    "maven.compiler.release",
                    "maven.compiler.target",
                    "maven.compiler.source",
                    "java.version",
                    "target",
                    "source"
            }) {
                level = resolvedText(firstText(document, tag), properties);
                if (level != null) {
                    break;
                }
            }
            return level == null ? null : new LanguageLevelResolution(normalize(level), false, "pom");
        } catch (RuntimeException e) {
            return new LanguageLevelResolution(DEFAULT_LEVEL, true, "ASSUMED");
        }
    }

    // 作用：从 Gradle 构建脚本推断语言级别；逻辑：先匹配 options.release，再匹配 source/targetCompatibility。
    private LanguageLevelResolution resolveGradle(Path gradle) {
        try {
            String content = Files.readString(gradle, StandardCharsets.UTF_8);
            Matcher release = GRADLE_RELEASE.matcher(content);
            if (release.find()) {
                return new LanguageLevelResolution(normalize(release.group(1)), false, "gradle.release");
            }
            Matcher compatibility = GRADLE_COMPATIBILITY.matcher(content);
            if (compatibility.find()) {
                return new LanguageLevelResolution(normalize(compatibility.group(1)), false, "gradle.compatibility");
            }
            return null;
        } catch (IOException e) {
            return new LanguageLevelResolution(DEFAULT_LEVEL, true, "ASSUMED");
        }
    }

    // 作用：读取第一个非空标签文本；逻辑：遍历同名节点并返回首个去空白内容。
    private String firstText(Document document, String tagName) {
        NodeList nodes = document.getElementsByTagName(tagName);
        for (int i = 0; i < nodes.getLength(); i++) {
            String text = nodes.item(i).getTextContent();
            if (text != null && !text.isBlank()) {
                return text.trim();
            }
        }
        return null;
    }

    // 作用：收集 POM 属性；逻辑：遍历 properties 节点下的直接子元素，按名称首次登记其文本值。
    private Map<String, String> pomProperties(Document document) {
        Map<String, String> properties = new LinkedHashMap<>();
        NodeList sections = document.getElementsByTagName("properties");
        for (int sectionIndex = 0; sectionIndex < sections.getLength(); sectionIndex++) {
            var children = sections.item(sectionIndex).getChildNodes();
            for (int childIndex = 0; childIndex < children.getLength(); childIndex++) {
                if (children.item(childIndex) instanceof Element property
                        && !property.getTagName().isBlank()) {
                    properties.putIfAbsent(property.getTagName(), property.getTextContent().trim());
                }
            }
        }
        return properties;
    }

    // 作用：展开 Maven 属性占位符；逻辑：最多迭代八轮替换已知属性，仍含未解析占位符时返回空。
    private String resolvedText(String text, Map<String, String> properties) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String resolved = text.trim();
        for (int depth = 0; depth < 8 && MAVEN_PROPERTY.matcher(resolved).find(); depth++) {
            Matcher matcher = MAVEN_PROPERTY.matcher(resolved);
            StringBuffer buffer = new StringBuffer();
            boolean changed = false;
            while (matcher.find()) {
                String replacement = properties.get(matcher.group(1));
                if (replacement == null || replacement.isBlank() || replacement.contains(matcher.group(1))) {
                    continue;
                }
                matcher.appendReplacement(buffer, Matcher.quoteReplacement(replacement));
                changed = true;
            }
            matcher.appendTail(buffer);
            if (!changed) {
                break;
            }
            resolved = buffer.toString().trim();
        }
        return MAVEN_PROPERTY.matcher(resolved).find() ? null : resolved;
    }

    // 作用：规范化 Java 版本号；逻辑：去掉 1. 前缀，空值回退到默认级别。
    private String normalize(String level) {
        String value = level.trim().replace("1.", "");
        return value.isEmpty() ? DEFAULT_LEVEL : value;
    }
}

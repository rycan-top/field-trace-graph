package io.fieldtracegraph.javaanalysis.project;

import io.fieldtracegraph.javaanalysis.io.SafeXmlReader;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// 作用：读取项目中的 classpath 清单文件。
// 使用场景：使用 --classpath-file 指定 Maven 或 Gradle 导出的清单时。

/**
 * Reads the declared local dependency manifest. It only parses structure and
 * does not validate paths, hashes, or sizes; those concerns belong to
 * {@link ClasspathEntryValidator}.
 */
public final class ClasspathManifestReader {
    private static final String MANIFEST_NAME = ".semantic-classpath.xml";

    // 作用：读取项目根目录的 classpath 清单；逻辑：无清单时返回空，有则按根节点版本选择规范或旧版解析。
    public ClasspathManifest read(Path projectRoot) {
        Path manifest = projectRoot.resolve(MANIFEST_NAME);
        if (!Files.isRegularFile(manifest)) {
            return ClasspathManifest.empty();
        }
        Document document = SafeXmlReader.parse(manifest);
        Element root = document.getDocumentElement();
        if ("semanticClasspath".equals(root.getTagName())) {
            return readCanonical(projectRoot, root);
        }
        if ("classpath".equals(root.getTagName())) {
            return readLegacy(root);
        }
        throw new IllegalArgumentException("Unsupported classpath manifest root: " + root.getTagName());
    }

    // 作用：解析带版本的规范清单；逻辑：校验版本、模块来源集、相对路径、项目边界与 SHA-256 后生成条目。
    private ClasspathManifest readCanonical(Path projectRoot, Element root) {
        if (!"1".equals(root.getAttribute("version"))) {
            throw new IllegalArgumentException("Unsupported semantic classpath manifest version");
        }
        List<ClasspathEntry> entries = new ArrayList<>();
        for (Element module : children(root, "module")) {
            String moduleId = module.getAttribute("path").trim();
            if (moduleId.isEmpty()) {
                moduleId = ".";
            }
            String sourceSet = module.getAttribute("sourceSet").trim();
            if (!"main".equals(sourceSet) && !"test".equals(sourceSet)) {
                throw new IllegalArgumentException("Classpath module sourceSet must be main or test");
            }
            for (Element jar : children(module, "jar")) {
                String path = required(jar, "path");
                String sha256 = required(jar, "sha256");
                if (!sha256.matches("[0-9a-f]{64}")) {
                    throw new IllegalArgumentException("Classpath entry sha256 must be 64 lowercase hex characters");
                }
                if (Path.of(path).isAbsolute()) {
                    throw new IllegalArgumentException("Canonical classpath entries must be project-relative");
                }
                Path resolved = projectRoot.resolve(path).normalize().toAbsolutePath();
                if (!resolved.startsWith(projectRoot.toAbsolutePath().normalize())) {
                    throw new IllegalArgumentException("Classpath entry escapes the project root: " + path);
                }
                entries.add(new ClasspathEntry(path, sourceSet, moduleId, sha256));
            }
        }
        return new ClasspathManifest(List.copyOf(entries));
    }

    // 作用：解析旧版 classpath 清单；逻辑：遍历 entry 节点并读取可选的 scope 与 module 属性。
    private ClasspathManifest readLegacy(Element root) {
        NodeList nodes = root.getElementsByTagName("entry");
        List<ClasspathEntry> entries = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            if (!(nodes.item(i) instanceof Element element)) {
                continue;
            }
            String path = element.getAttribute("path");
            if (path == null || path.isBlank()) {
                throw new IllegalArgumentException("Classpath entry is missing a path");
            }
            entries.add(new ClasspathEntry(
                    path.trim(),
                    blankToNull(element.getAttribute("scope")),
                    blankToNull(element.getAttribute("module"))
            ));
        }
        return new ClasspathManifest(List.copyOf(entries));
    }

    // 作用：筛选直接子元素；逻辑：遍历同级节点并只保留标签名匹配的元素。
    private List<Element> children(Element parent, String name) {
        List<Element> result = new ArrayList<>();
        for (var node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element && name.equals(element.getTagName())) {
                result.add(element);
            }
        }
        return result;
    }

    // 作用：读取必填属性；逻辑：去空白后为空则抛错，否则返回该值。
    private String required(Element element, String attribute) {
        String value = element.getAttribute(attribute).trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("Classpath entry is missing " + attribute);
        }
        return value;
    }

    // 作用：把空白字符串归一为空值；逻辑：空白返回 null，否则返回去空白后的内容。
    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}

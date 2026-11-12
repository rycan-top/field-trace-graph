package io.fieldtracegraph.javaanalysis.semantic.javac;

import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.SourcePositions;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;
import io.fieldtracegraph.javaanalysis.semantic.protocol.SemanticSource;

import javax.lang.model.element.Element;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.TypeElement;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// 作用：使用 javac 完成真实的类型归属。
// 使用场景：解决重载、泛型与继承场景下的调用消歧。

/**
 * Runs javac over an in-memory set of sources to collect compile-time
 * diagnostics and resolved method targets. It never manages processes, scans
 * files, or writes the index; isolation and resource limits are handled by the
 * semantic worker.
 */
public final class JavacSemanticAnalyzer {
    private static final int MAX_DIAGNOSTICS = 50;

    // 作用：执行内存源码的 javac 分析；逻辑：建立内存文件对象，解析并分析类型，收集错误诊断与调用归属。
    public SemanticAnalysisResult analyze(List<SemanticSource> sources, List<Path> classpath, String encoding) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new SemanticAnalysisResult(
                    List.of(new Diagnostic("COMPILER_UNAVAILABLE", "System Java compiler is not available", null, null, false, false)),
                    List.of()
            );
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        List<JavaFileObject> fileObjects = new ArrayList<>();
        Map<URI, String> relativePaths = new LinkedHashMap<>();
        for (SemanticSource source : sources) {
            if (source.className() != null) {
                SourceFileObject fileObject = new SourceFileObject(
                        source.className(),
                        source.relativePath(),
                        source.content()
                );
                fileObjects.add(fileObject);
                relativePaths.put(fileObject.toUri().normalize(), source.relativePath());
            }
        }
        if (fileObjects.isEmpty()) {
            return new SemanticAnalysisResult(List.of(), List.of());
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(collector, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(null, fileManager, collector, options(classpath, encoding), null, fileObjects);
            Iterable<? extends CompilationUnitTree> units = task.parse();
            task.analyze();
            List<Diagnostic> diagnostics = diagnostics(collector);
            List<CallAttribution> attributions = attributions(task, units, relativePaths);
            return new SemanticAnalysisResult(diagnostics, attributions);
        } catch (IOException e) {
            return new SemanticAnalysisResult(
                    List.of(new Diagnostic("SEMANTIC_FAILED", e.getMessage(), null, null, false, false)),
                    List.of()
            );
        }
    }

    // 作用：提取编译错误诊断；逻辑：只保留 ERROR 级别并有数量上限地转换为内部诊断。
    private List<Diagnostic> diagnostics(DiagnosticCollector<JavaFileObject> collector) {
        List<Diagnostic> result = new ArrayList<>();
        for (javax.tools.Diagnostic<? extends JavaFileObject> diagnostic : collector.getDiagnostics()) {
            if (diagnostic.getKind() == javax.tools.Diagnostic.Kind.ERROR && result.size() < MAX_DIAGNOSTICS) {
                result.add(toDiagnostic(diagnostic));
            }
        }
        return List.copyOf(result);
    }

    // 作用：扫描已解析语法树收集调用归属；逻辑：用 Trees 解析方法和构造器目标，并逐编译单元执行扫描器。
    private List<CallAttribution> attributions(
            JavacTask task,
            Iterable<? extends CompilationUnitTree> units,
            Map<URI, String> relativePaths
    ) {
        Trees trees = Trees.instance(task);
        List<CallAttribution> result = new ArrayList<>();
        TreePathScanner<Void, Void> scanner = new TreePathScanner<>() {
            @Override
            // 作用：处理方法调用节点；逻辑：解析被调可执行元素，非构造器时登记归属并继续遍历子节点。
            public Void visitMethodInvocation(MethodInvocationTree node, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (element instanceof ExecutableElement method
                        && !"<init>".equals(method.getSimpleName().toString())) {
                    addAttribution(
                            node,
                            method,
                            getCurrentPath().getCompilationUnit(),
                            trees,
                            relativePaths,
                            result
                    );
                }
                return super.visitMethodInvocation(node, unused);
            }

            @Override
            // 作用：处理 new 表达式节点；逻辑：解析构造器元素并登记归属，随后继续遍历子节点。
            public Void visitNewClass(NewClassTree node, Void unused) {
                Element element = trees.getElement(getCurrentPath());
                if (element instanceof ExecutableElement constructor) {
                    addAttribution(
                            node,
                            constructor,
                            getCurrentPath().getCompilationUnit(),
                            trees,
                            relativePaths,
                            result
                    );
                }
                return super.visitNewClass(node, unused);
            }
        };
        for (CompilationUnitTree unit : units) {
            scanner.scan(unit, null);
        }
        return List.copyOf(result);
    }

    // 作用：登记单个调用归属；逻辑：定位源码路径与起始行列，提取声明类型、参数类型和可变参数标记。
    private void addAttribution(
            Tree node,
            ExecutableElement executable,
            CompilationUnitTree unit,
            Trees trees,
            Map<URI, String> relativePaths,
            List<CallAttribution> result
    ) {
        String relativePath = relativePaths.get(unit.getSourceFile().toUri().normalize());
        if (relativePath == null && unit.getSourceFile() instanceof SourceFileObject source) {
            relativePath = source.relativePath();
        }
        if (relativePath == null) {
            return;
        }
        SourcePositions positions = trees.getSourcePositions();
        long startPosition = positions.getStartPosition(unit, node);
        int line = (int) unit.getLineMap().getLineNumber(startPosition);
        int column = (int) unit.getLineMap().getColumnNumber(startPosition);
        String methodName = executable.getSimpleName().toString();
        int argumentCount = actualArgumentCount(node, executable);
        Element owner = executable.getEnclosingElement();
        String declaringTypeQname = owner instanceof TypeElement typeElement
                ? typeElement.getQualifiedName().toString()
                : String.valueOf(owner);
        List<String> parameterTypes = executable.getParameters().stream()
                .map(parameter -> parameter.asType().toString())
                .toList();
        result.add(new CallAttribution(
                relativePath,
                line,
                column,
                methodName,
                argumentCount,
                declaringTypeQname,
                parameterTypes,
                executable.isVarArgs()
        ));
    }

    // 作用：计算调用点的实际实参数量；逻辑：方法调用与构造分别取参数列表大小，其他节点回退到形参数量。
    private int actualArgumentCount(Tree node, ExecutableElement executable) {
        if (node instanceof MethodInvocationTree invocation) {
            return invocation.getArguments().size();
        }
        if (node instanceof NewClassTree creation) {
            return creation.getArguments().size();
        }
        return executable.getParameters().size();
    }

    // 作用：构造 javac 编译选项；逻辑：关闭注解处理并设置编码，classpath 非空时追加依赖路径。
    private List<String> options(List<Path> classpath, String encoding) {
        List<String> options = new ArrayList<>(List.of("-proc:none", "-encoding", encoding == null ? "UTF-8" : encoding));
        if (!classpath.isEmpty()) {
            options.add("-classpath");
            options.add(classpath.stream().map(Path::toString).collect(Collectors.joining(java.io.File.pathSeparator)));
        }
        return options;
    }

    // 作用：把 javac 诊断转换为内部模型；逻辑：从内存源文件取相对路径，并保留消息与行号。
    private Diagnostic toDiagnostic(javax.tools.Diagnostic<? extends JavaFileObject> diagnostic) {
        String relativePath = diagnostic.getSource() instanceof SourceFileObject sourceFileObject
                ? sourceFileObject.relativePath()
                : null;
        return new Diagnostic(
                "SEMANTIC_DIAGNOSTIC",
                diagnostic.getMessage(null),
                relativePath,
                (int) diagnostic.getLineNumber(),
                false,
                false
        );
    }

    private static final class SourceFileObject extends SimpleJavaFileObject {
        private final String content;
        private final String relativePath;

        // 作用：创建内存源码对象；逻辑：按类名生成 URI，并保存相对路径与源码文本。
        SourceFileObject(String className, String relativePath, String content) {
            super(URI.create("string:///" + className.replace('.', '/') + ".java"), Kind.SOURCE);
            this.relativePath = relativePath;
            this.content = content;
        }

        @Override
        // 作用：提供源码内容给编译器；逻辑：直接返回保存的源码文本。
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return content;
        }

        // 作用：返回源码相对路径；逻辑：读取构造时保存的路径。
        String relativePath() {
            return relativePath;
        }
    }
}

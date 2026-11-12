package io.fieldtracegraph.javaanalysis;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ParseResult;
import com.github.javaparser.Problem;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.TypeDeclaration;
import io.fieldtracegraph.core.model.AnalysisResult;
import io.fieldtracegraph.core.model.Diagnostic;
import io.fieldtracegraph.core.model.SourceFileRecord;
import io.fieldtracegraph.core.model.SourceScanOptions;
import io.fieldtracegraph.core.model.ValueNodeRecord;
import io.fieldtracegraph.core.port.SourceAnalyzer;
import io.fieldtracegraph.javaanalysis.extractor.AssignmentFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.AccessorFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.CallSiteExtractor;
import io.fieldtracegraph.javaanalysis.extractor.CallValueFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.CallableScopeExtractor;
import io.fieldtracegraph.javaanalysis.extractor.BeanCopyExtractor;
import io.fieldtracegraph.javaanalysis.extractor.BeanSerializationFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.BuilderFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ContainerFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ContainerFactoryFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ContainerSerializationFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ConstructorFieldFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.MethodSummaryExtractor;
import io.fieldtracegraph.javaanalysis.extractor.MethodReferenceExtractor;
import io.fieldtracegraph.javaanalysis.extractor.MapStructMappingExtractor;
import io.fieldtracegraph.javaanalysis.extractor.LambdaParameterFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.SequentialFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.StreamFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ValueNodeExtractor;
import io.fieldtracegraph.javaanalysis.mybatis.MyBatisFieldLineageExtractor;
import io.fieldtracegraph.javaanalysis.flow.JavaValueFlowContext;
import io.fieldtracegraph.javaanalysis.project.ClasspathManifestReader;
import io.fieldtracegraph.javaanalysis.project.ClasspathEntryValidator;
import io.fieldtracegraph.javaanalysis.project.ClasspathManifest;
import io.fieldtracegraph.javaanalysis.project.ClasspathTypeIndex;
import io.fieldtracegraph.javaanalysis.project.ValidatedClasspath;
import io.fieldtracegraph.javaanalysis.project.JavaModuleCatalog;
import io.fieldtracegraph.javaanalysis.resolution.MethodCallResolver;
import io.fieldtracegraph.javaanalysis.resolution.ExternalConstructorFieldCatalog;
import io.fieldtracegraph.javaanalysis.resolution.SourceTypeCatalog;
import io.fieldtracegraph.javaanalysis.semantic.cache.SemanticAnalysisCache;
import io.fieldtracegraph.javaanalysis.semantic.source.CompilationSourceCollector;
import io.fieldtracegraph.javaanalysis.semantic.source.CompilationSourceSet;
import io.fieldtracegraph.javaanalysis.semantic.source.SourcePackageIndex;
import io.fieldtracegraph.javaanalysis.semantic.source.SourcePackageIndexBuilder;
import io.fieldtracegraph.javaanalysis.semantic.javac.SemanticAnalysisResult;
import io.fieldtracegraph.javaanalysis.semantic.protocol.CallAttribution;
import io.fieldtracegraph.javaanalysis.semantic.snapshot.ClasspathSnapshot;
import io.fieldtracegraph.javaanalysis.semantic.snapshot.ClasspathSnapshotPreparer;
import io.fieldtracegraph.javaanalysis.semantic.worker.SemanticWorkerLauncher;

import java.nio.file.Path;
import java.net.URL;
import java.net.URLClassLoader;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：Java 源码分析总调度。
// 使用场景：索引服务调用，串起解析、类型目录、语义分析、提取与汇总。

public final class JavaSourceAnalyzer implements SourceAnalyzer {
    private final JavaParserFactory parserFactory = new JavaParserFactory();
    private final CallableScopeExtractor scopeExtractor = new CallableScopeExtractor();
    private final ValueNodeExtractor valueNodeExtractor = new ValueNodeExtractor();
    private final AssignmentFlowExtractor assignmentFlowExtractor = new AssignmentFlowExtractor();
    private final AccessorFlowExtractor accessorFlowExtractor = new AccessorFlowExtractor();
    private final SequentialFlowExtractor sequentialFlowExtractor = new SequentialFlowExtractor();
    private final ContainerFlowExtractor containerFlowExtractor = new ContainerFlowExtractor();
    private final ContainerFactoryFlowExtractor containerFactoryFlowExtractor = new ContainerFactoryFlowExtractor();
    private final ContainerSerializationFlowExtractor containerSerializationFlowExtractor =
            new ContainerSerializationFlowExtractor();
    private final BeanCopyExtractor beanCopyExtractor = new BeanCopyExtractor();
    private final BeanSerializationFlowExtractor beanSerializationFlowExtractor =
            new BeanSerializationFlowExtractor();
    private final BuilderFlowExtractor builderFlowExtractor = new BuilderFlowExtractor();
    private final CallSiteExtractor callSiteExtractor = new CallSiteExtractor();
    private final CallValueFlowExtractor callValueFlowExtractor = new CallValueFlowExtractor();
    private final MethodSummaryExtractor methodSummaryExtractor = new MethodSummaryExtractor();
    private final MapStructMappingExtractor mapStructMappingExtractor = new MapStructMappingExtractor();
    private final SemanticWorkerLauncher semanticWorkerLauncher = new SemanticWorkerLauncher();
    private final ClasspathManifestReader classpathManifestReader = new ClasspathManifestReader();
    private final ClasspathEntryValidator classpathEntryValidator = new ClasspathEntryValidator();
    private final ClasspathSnapshotPreparer classpathSnapshotPreparer = new ClasspathSnapshotPreparer();
    private final CompilationSourceCollector compilationSourceCollector = new CompilationSourceCollector();

    // 作用：以默认选项分析源码；逻辑：套用默认扫描选项后调用完整分析入口。
    @Override
    public AnalysisResult analyze(List<SourceFileRecord> sourceFiles, Path projectRoot) {
        return analyze(sourceFiles, projectRoot, SourceScanOptions.defaults());
    }

    // 作用：执行完整源码分析；逻辑：解析文件、建类型目录与 classpath 索引，跑语义分析后逐文件提取并汇总。
    @Override
    public AnalysisResult analyze(
            List<SourceFileRecord> sourceFiles,
            Path projectRoot,
            SourceScanOptions options
    ) {
        JavaAnalysisAccumulator accumulator = new JavaAnalysisAccumulator();
        JavaModuleCatalog moduleCatalog = JavaModuleCatalog.build(sourceFiles);
        Map<SourceFileRecord, CompilationUnit> units = parse(sourceFiles, moduleCatalog, accumulator);
        Set<String> parsedPaths = units.keySet().stream()
                .map(SourceFileRecord::relativePath)
                .collect(Collectors.toSet());
        List<SourceFileRecord> publishedFiles = sourceFiles.stream()
                .map(file -> parsedPaths.contains(file.relativePath())
                        ? file
                        : file.withAnalysisStatus(
                                "PARTIAL",
                                file.analysisIssueCode() == null
                                        ? "PARSE_FAILED"
                                        : file.analysisIssueCode()
                        ))
                .toList();
        SourcePackageIndex packageIndex = new SourcePackageIndexBuilder().build(units);
        SourceTypeCatalog catalog = SourceTypeCatalog.build(units, packageIndex);
        ClasspathManifest manifest = classpathManifestReader.read(projectRoot);
        ValidatedClasspath validatedClasspath = classpathEntryValidator.validate(
                projectRoot,
                manifest,
                options.classpathEntries()
        );
        ClasspathTypeIndex classpathTypes = ClasspathTypeIndex.load(validatedClasspath.paths());
        if (classpathTypes.truncated()) {
            accumulator.addDiagnostic(new Diagnostic(
                    "CLASSPATH_INDEX_TRUNCATED",
                    "Classpath metadata index reached its class limit; "
                            + "boundary reasons may be less precise. Reduce --classpath entries.",
                    null,
                    null,
                    false,
                    false
            ));
        }
        CompilationSourceSet sourceSet = compilationSourceCollector.collect(units);
        SemanticAnalysisResult semantic;
        try (ClasspathSnapshot snapshot = classpathSnapshotPreparer.prepare(validatedClasspath)) {
            semantic = semanticWorkerLauncher.analyze(sourceSet.sources(), snapshot.paths(), "UTF-8");
        }
        accumulator.addDiagnostics(semantic.diagnostics());
        SemanticAnalysisCache semanticCache = new SemanticAnalysisCache();
        for (CallAttribution attribution : semantic.attributions()) {
            semanticCache.put(attribution.key(), attribution);
        }
        Map<String, CallAttribution> attributions = semanticCache.snapshot();
        MethodCallResolver resolver = new MethodCallResolver(catalog, attributions, classpathTypes);
        MethodReferenceExtractor methodReferenceExtractor = new MethodReferenceExtractor(catalog);
        LambdaParameterFlowExtractor lambdaParameterFlowExtractor = new LambdaParameterFlowExtractor();

        try (URLClassLoader externalClassLoader = externalClassLoader(validatedClasspath)) {
            ConstructorFieldFlowExtractor constructorFieldFlowExtractor = new ConstructorFieldFlowExtractor(
                    catalog,
                    new ExternalConstructorFieldCatalog(externalClassLoader)
            );
            StreamFlowExtractor streamFlowExtractor = new StreamFlowExtractor(catalog);
            for (var entry : units.entrySet()) {
                extractFile(entry.getKey(), entry.getValue(), resolver,
                        methodReferenceExtractor, lambdaParameterFlowExtractor,
                        constructorFieldFlowExtractor, streamFlowExtractor, catalog, accumulator);
            }
            new MyBatisFieldLineageExtractor().extract(projectRoot, units, accumulator);
        } catch (IOException e) {
            accumulator.addDiagnostic(new Diagnostic(
                    "CLASSPATH_LOADER_FAILED",
                    e.getMessage(),
                    null,
                    null,
                    false,
                    false
            ));
        }
        return accumulator.toResult(publishedFiles);
    }

    // 作用：创建外部类加载器；逻辑：把校验后的 classpath 条目转为 URL 并构造父子委派的类加载器。
    private URLClassLoader externalClassLoader(ValidatedClasspath classpath) {
        URL[] urls = classpath.paths().stream()
                .map(path -> {
                    try {
                        return path.toUri().toURL();
                    } catch (IOException e) {
                        throw new IllegalArgumentException("Invalid classpath entry: " + path, e);
                    }
                })
                .toArray(URL[]::new);
        return new URLClassLoader(urls, JavaSourceAnalyzer.class.getClassLoader());
    }

    // 作用：解析源文件；逻辑：跳过已标记问题的文件，逐文件按模块语言级别解析并记录失败诊断。
    private Map<SourceFileRecord, CompilationUnit> parse(
            List<SourceFileRecord> sourceFiles,
            JavaModuleCatalog moduleCatalog,
            JavaAnalysisAccumulator accumulator
    ) {
        Map<SourceFileRecord, CompilationUnit> units = new LinkedHashMap<>();
        for (SourceFileRecord source : sourceFiles) {
            if (source.analysisIssueCode() != null
                    && !source.analysisIssueCode().isBlank()) {
                accumulator.addDiagnostic(new Diagnostic(
                        source.analysisIssueCode(),
                        "Source file could not be analysed: " + source.relativePath(),
                        source.relativePath(),
                        null,
                        false,
                        false
                ));
                continue;
            }
            try {
                JavaParser parser = parserFactory.create(moduleCatalog.languageLevel(source.moduleId()));
                ParseResult<CompilationUnit> result = parser.parse(source.content());
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    units.put(source, result.getResult().get());
                } else {
                    Problem problem = result.getProblems().stream().findFirst().orElse(null);
                    String message = problem == null ? "Unable to parse source" : problem.getMessage();
                    Integer line = problem == null ? null : problem.getLocation()
                            .flatMap(location -> location.getBegin().getRange())
                            .map(range -> range.begin.line)
                            .orElse(null);
                    accumulator.addDiagnostic(new Diagnostic("PARSE_FAILED", message, source.relativePath(), line, true, true));
                }
            } catch (Exception e) {
                accumulator.addDiagnostic(new Diagnostic("PARSE_FAILED", e.getMessage(), source.relativePath(), null, true, true));
            }
        }
        return units;
    }

    // 作用：提取单个文件的图数据；逻辑：为每个可调用体建上下文，依次运行各类提取器写入累积器。
    private void extractFile(
            SourceFileRecord source,
            CompilationUnit unit,
            MethodCallResolver resolver,
            MethodReferenceExtractor methodReferenceExtractor,
            LambdaParameterFlowExtractor lambdaParameterFlowExtractor,
            ConstructorFieldFlowExtractor constructorFieldFlowExtractor,
            StreamFlowExtractor streamFlowExtractor,
            SourceTypeCatalog catalog,
            JavaAnalysisAccumulator accumulator
    ) {
        Map<String, TypeDeclaration<?>> typesByQname = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            typesByQname.put(type.getFullyQualifiedName().orElse(type.getNameAsString()), type);
        }
        List<CallableScope> scopes = scopeExtractor.extract(source, unit, accumulator);
        for (CallableScope scope : scopes) {
            TypeDeclaration<?> type = typesByQname.get(scope.typeQname());
            JavaValueFlowContext context = JavaValueFlowContext.from(scope.callable(), type, catalog);
            Map<String, ValueNodeRecord> nodes = valueNodeExtractor.extract(source, scope, accumulator, context).latest();
            lambdaParameterFlowExtractor.extract(source, scope, nodes, accumulator, context);
            assignmentFlowExtractor.extract(source, scope, nodes, accumulator, context);
            accessorFlowExtractor.extract(source, scope, nodes, accumulator, context);
            sequentialFlowExtractor.extract(source, scope, nodes, accumulator, context);
            containerFlowExtractor.extract(source, scope, nodes, accumulator, context);
            containerFactoryFlowExtractor.extract(source, scope, nodes, accumulator, context);
            containerSerializationFlowExtractor.extract(source, scope, nodes, accumulator, context);
            beanSerializationFlowExtractor.extract(source, scope, nodes, accumulator, context, catalog);
            constructorFieldFlowExtractor.extract(source, scope, nodes, accumulator, context);
            streamFlowExtractor.extract(source, scope, nodes, accumulator, context);
            beanCopyExtractor.extract(source, scope, nodes, accumulator, context, catalog);
            builderFlowExtractor.extract(source, scope, nodes, accumulator, context);
            callValueFlowExtractor.extract(source, scope, nodes, accumulator, context);
            callSiteExtractor.extract(source, unit, scope, nodes, resolver, accumulator, context);
            methodReferenceExtractor.extract(source, scope, nodes, accumulator, context);
            methodSummaryExtractor.extract(
                    source,
                    scope,
                    type == null ? unit.getType(0) : type,
                    nodes,
                    accumulator,
                    context
            );
            mapStructMappingExtractor.extract(
                    source,
                    scope,
                    type == null ? unit.getType(0) : type,
                    nodes,
                    accumulator,
                    context,
                    catalog
            );
        }
    }
}

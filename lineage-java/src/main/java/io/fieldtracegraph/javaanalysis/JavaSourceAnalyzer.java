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
import io.fieldtracegraph.javaanalysis.extractor.ContainerProjectionFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ContainerFactoryFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ContainerSerializationFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.ConstructorFieldFlowExtractor;
import io.fieldtracegraph.javaanalysis.extractor.FunctionalWrapperFlowExtractor;
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
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

// 作用：Java 源码分析总调度。
// 使用场景：索引服务调用，串起解析、类型目录、语义分析、提取与汇总。

public final class JavaSourceAnalyzer implements SourceAnalyzer {
    private static final List<String> LANGUAGE_LEVELS = List.of(
            "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "21"
    );

    private final JavaParserFactory parserFactory = new JavaParserFactory();
    private final CallableScopeExtractor scopeExtractor = new CallableScopeExtractor();
    private final ValueNodeExtractor valueNodeExtractor = new ValueNodeExtractor();
    private final AssignmentFlowExtractor assignmentFlowExtractor = new AssignmentFlowExtractor();
    private final AccessorFlowExtractor accessorFlowExtractor = new AccessorFlowExtractor();
    private final SequentialFlowExtractor sequentialFlowExtractor = new SequentialFlowExtractor();
    private final ContainerFlowExtractor containerFlowExtractor = new ContainerFlowExtractor();
    private final ContainerProjectionFlowExtractor containerProjectionFlowExtractor =
            new ContainerProjectionFlowExtractor();
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
        return analyzeInternal(sourceFiles, projectRoot, options, 0L);
    }

    /**
     * Indexing a very large repository must be able to stop inside the run,
     * not after it has already spent the whole budget. The per-file parse and
     * extraction loops check the deadline, so {@code --index-timeout} bounds
     * the wait instead of discarding completed work later.
     */
    // 作用：带截止时间执行分析；逻辑：解析与逐文件提取阶段均检查预算，超时即中断。
    @Override
    public AnalysisResult analyze(
            List<SourceFileRecord> sourceFiles,
            Path projectRoot,
            SourceScanOptions options,
            long deadlineNanos
    ) {
        return analyzeInternal(sourceFiles, projectRoot, options, deadlineNanos);
    }

    // 作用：执行完整分析；逻辑：按阶段推进，每阶段与每个文件前检查索引预算。
    private AnalysisResult analyzeInternal(
            List<SourceFileRecord> sourceFiles,
            Path projectRoot,
            SourceScanOptions options,
            long deadlineNanos
    ) {
        JavaAnalysisAccumulator accumulator = new JavaAnalysisAccumulator();
        JavaModuleCatalog moduleCatalog = JavaModuleCatalog.build(sourceFiles);
        Map<SourceFileRecord, CompilationUnit> units =
                parse(sourceFiles, moduleCatalog, accumulator, deadlineNanos);
        ensureBudget(deadlineNanos);
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
        ensureBudget(deadlineNanos);
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
        ensureBudget(deadlineNanos);
        SemanticAnalysisResult semantic;
        try (ClasspathSnapshot snapshot = classpathSnapshotPreparer.prepare(validatedClasspath)) {
            semantic = semanticWorkerLauncher.analyze(sourceSet.sources(), snapshot.paths(), "UTF-8");
        }
        ensureBudget(deadlineNanos);
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
            FunctionalWrapperFlowExtractor functionalWrapperFlowExtractor =
                    new FunctionalWrapperFlowExtractor();
            MyBatisFieldLineageExtractor myBatisExtractor = new MyBatisFieldLineageExtractor();
            List<MyBatisFieldLineageExtractor.IbatisCallSite> ibatisCalls = new ArrayList<>();
            Set<String> mapperNamespaces = myBatisExtractor.mapperNamespaces(projectRoot);
            /*
             * Keep the catalog built from all ASTs, but release each file's AST
             * as soon as extraction is done. Mapper interfaces stay because the
             * MyBatis phase resolves XML statements against their declarations;
             * iBatis call sites are reduced to lightweight records first.
             * This removes the "all compilation units live while all edges are
             * accumulated" peak that forced -Xmx6g on large repositories.
             */
            Iterator<Map.Entry<SourceFileRecord, CompilationUnit>> unitIterator =
                    units.entrySet().iterator();
            while (unitIterator.hasNext()) {
                var entry = unitIterator.next();
                ensureBudget(deadlineNanos);
                myBatisExtractor.collectIbatisCalls(entry.getKey(), entry.getValue(), ibatisCalls);
                extractFile(entry.getKey(), entry.getValue(), resolver,
                        methodReferenceExtractor, lambdaParameterFlowExtractor,
                        constructorFieldFlowExtractor, streamFlowExtractor,
                        functionalWrapperFlowExtractor, catalog, classpathTypes, accumulator);
                if (!declaresMapperType(entry.getValue(), mapperNamespaces)) {
                    unitIterator.remove();
                }
            }
            ensureBudget(deadlineNanos);
            myBatisExtractor.extract(projectRoot, units, ibatisCalls, accumulator);
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

    // 作用：判断文件是否声明 Mapper 类型；逻辑：只有 XML 命名空间对应的 AST 需保留到 MyBatis 阶段。
    private boolean declaresMapperType(CompilationUnit unit, Set<String> mapperNamespaces) {
        if (mapperNamespaces.isEmpty()) {
            return false;
        }
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            String qname = type.getFullyQualifiedName()
                    .orElseGet(type::getNameAsString);
            if (mapperNamespaces.contains(qname)) {
                return true;
            }
            /*
             * iBatis/MyBatis DAO implementations declare the XML namespace on
             * {@code @Dao("brandMoreDao")} / {@code @Mapper} rather than in the
             * type name. Without these ASTs the XML statements cannot be bound
             * to a Java method and every SQL parameter edge disappears.
             */
            for (var annotation : type.getAnnotations()) {
                String name = annotation.getNameAsString();
                if ("Dao".equals(name) || "Mapper".equals(name)
                        || name.endsWith(".Dao") || name.endsWith(".Mapper")) {
                    return true;
                }
            }
            String simple = type.getNameAsString();
            for (String namespace : mapperNamespaces) {
                if (namespace.equals(simple) || namespace.endsWith("." + simple)) {
                    return true;
                }
            }
        }
        return false;
    }

    // 作用：检查索引预算；逻辑：截止时间为 0 表示不限时，超时抛出可识别的 INDEX_TIMEOUT。
    private void ensureBudget(long deadlineNanos) {
        if (deadlineNanos > 0 && System.nanoTime() > deadlineNanos) {
            throw new IllegalStateException("INDEX_TIMEOUT: analysis exceeded the time budget");
        }
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

    // 作用：解析源文件；逻辑：跳过已标记问题的文件，逐文件按模块语言级别解析，必要时向上兼容重试。
    private Map<SourceFileRecord, CompilationUnit> parse(
            List<SourceFileRecord> sourceFiles,
            JavaModuleCatalog moduleCatalog,
            JavaAnalysisAccumulator accumulator,
            long deadlineNanos
    ) {
        Map<SourceFileRecord, CompilationUnit> units = new LinkedHashMap<>();
        for (SourceFileRecord source : sourceFiles) {
            ensureBudget(deadlineNanos);
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
            String declaredLevel = moduleCatalog.languageLevel(source.moduleId());
            ParseOutcome outcome = parseWithEscalation(source, declaredLevel);
            if (outcome.unit() != null) {
                units.put(source, outcome.unit());
                if (outcome.level() != null && !outcome.level().equals(normalizeLevel(declaredLevel))) {
                    accumulator.addDiagnostic(new Diagnostic(
                            "LANGUAGE_LEVEL_ESCALATED",
                            "Parsed as Java " + outcome.level() + " although the module declares Java "
                                    + declaredLevel + "; first problem at the declared level: "
                                    + outcome.detail(),
                            source.relativePath(),
                            outcome.line(),
                            false,
                            false
                    ));
                }
            } else {
                accumulator.addDiagnostic(new Diagnostic(
                        "PARSE_FAILED",
                        outcome.detail(),
                        source.relativePath(),
                        outcome.line(),
                        true,
                        true
                ));
            }
        }
        return units;
    }

    // 作用：按模块语言级别解析，失败时逐级向上重试；逻辑：源码含高于声明级别的语法时仍可建模，
    // 例如 POM 属性写 1.7 但插件配置为 8、或源码实际使用了 lambda/var/record。
    private ParseOutcome parseWithEscalation(SourceFileRecord source, String declaredLevel) {
        List<String> candidates = escalationCandidates(declaredLevel);
        String firstDetail = "Unable to parse source";
        Integer firstLine = null;
        boolean first = true;
        for (String level : candidates) {
            try {
                JavaParser parser = parserFactory.create(level);
                ParseResult<CompilationUnit> result = parser.parse(source.content());
                if (result.isSuccessful() && result.getResult().isPresent()) {
                    return new ParseOutcome(result.getResult().get(), level,
                            first ? null : firstDetail, first ? null : firstLine);
                }
                Problem problem = result.getProblems().stream().findFirst().orElse(null);
                String message = problem == null ? "Unable to parse source" : problem.getMessage();
                Integer line = problem == null ? null : problem.getLocation()
                        .flatMap(location -> location.getBegin().getRange())
                        .map(range -> range.begin.line)
                        .orElse(null);
                if (first) {
                    firstDetail = "as Java " + level + ": " + message;
                    firstLine = line;
                    first = false;
                }
            } catch (RuntimeException e) {
                if (first) {
                    firstDetail = "as Java " + level + ": " + e.getMessage();
                    first = false;
                }
            }
        }
        return new ParseOutcome(null, null, firstDetail, firstLine);
    }

    // 作用：生成语言级别重试序列；逻辑：从声明级别开始按升序补齐更高版本，声明值未知时回退到 17。
    private List<String> escalationCandidates(String declaredLevel) {
        String normalized = normalizeLevel(declaredLevel);
        int start = LANGUAGE_LEVELS.indexOf(normalized);
        if (start < 0) {
            start = LANGUAGE_LEVELS.indexOf("17");
        }
        return LANGUAGE_LEVELS.subList(start, LANGUAGE_LEVELS.size());
    }

    // 作用：规范化语言级别；逻辑：去掉 1. 前缀与空白，空值按 17 处理。
    private String normalizeLevel(String level) {
        if (level == null || level.isBlank()) {
            return "17";
        }
        String normalized = level.trim();
        return normalized.startsWith("1.") ? normalized.substring(2) : normalized;
    }

    // 作用：承载单文件解析结果；逻辑：记录生效语言级别、首个问题描述与行号。
    private record ParseOutcome(CompilationUnit unit, String level, String detail, Integer line) {
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
            FunctionalWrapperFlowExtractor functionalWrapperFlowExtractor,
            SourceTypeCatalog catalog,
            ClasspathTypeIndex classpathTypes,
            JavaAnalysisAccumulator accumulator
    ) {
        Map<String, TypeDeclaration<?>> typesByQname = new LinkedHashMap<>();
        for (TypeDeclaration<?> type : unit.findAll(TypeDeclaration.class)) {
            typesByQname.put(type.getFullyQualifiedName().orElse(type.getNameAsString()), type);
        }
        List<CallableScope> scopes = scopeExtractor.extract(source, unit, accumulator);
        for (CallableScope scope : scopes) {
            TypeDeclaration<?> type = typesByQname.get(scope.typeQname());
            JavaValueFlowContext context =
                    JavaValueFlowContext.from(scope.callable(), type, catalog, classpathTypes);
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
            containerProjectionFlowExtractor.extract(source, scope, nodes, accumulator, context);
            functionalWrapperFlowExtractor.extract(source, scope, nodes, accumulator, context);
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

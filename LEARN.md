根目录
- README.md — 作用：唯一权威手册；场景：第一次上手、查命令参数、对照发布步骤。
- To-do/RELEASE-CHECKLIST.md — 作用：fail-closed 发布清单；场景：每次发版逐条确认证据（与 To-do/TODO.md 同目录）。
- THIRD-PARTY-NOTICES — 作用：第三方依赖与许可声明；场景：打归档、法务审查。
- pom.xml — 作用：多模块构建与版本唯一来源；场景：加依赖、升版本、跑 mvn。
- .gitignore — 作用：排除生成物；场景：保持 git status 干净、避免误提交索引库。
- .gitleaks.toml — 作用：密钥扫描规则与白名单；场景：CI 和 security-scan.sh 扫描源码。
- .syft.yaml — 作用：SBOM 生成排除规则；场景：发布时生成依赖清单。
- .github/workflows/verify.yml — 作用：日常 CI 验证；场景：每次 push/PR 跑测试与 npm 启动器校验。
- .github/workflows/release.yml — 作用：正式发布流水线；场景：打 tag 后三平台构建、扫描、签名、`npm pack`。
- .field-trace-graph/ — 作用：FTG 自身索引库与写锁；场景：在本仓库执行 trace 时读取，删掉只需重新 index。
- target/field-trace-graph-sbom.json — 作用：Maven 生成的 SBOM；场景：发布门禁校验依赖完整性。
- security-reports/*.json — 作用：源码、归档、npm 包三次 gitleaks 结果；场景：发布审计留痕。
  lineage-core — 模型与契约（82 个）

index/（3）
- IndexChangePlanner — 作用：对比新旧文件 hash 得出增删改；场景：每次增量索引开始。
- IndexChangeSet — 作用：承载三类变更集合；场景：传给分析器与发布器决定重算范围。
- AffectedScopeResolver — 作用：由调用关系反推受影响方法；场景：增量索引时缩小重算范围。
  model/ 证据与置信度（9）
- Resolution — 作用：解析强度枚举；场景：边、边界、质量统计、JSON 输出。
- Provenance — 作用：证据来源枚举；场景：区分源码/字节码/适配器/运行时证据。
- EdgeEvidence — 作用：一条逻辑边的一份独立证据；场景：写入 edge_evidence，支持多证据叠加。
- BoundaryReason — 作用：边界原因码枚举；场景：开放边界统计、`--fail-on-reason` 门禁。
- BoundaryAction — 作用：建议动作枚举；场景：把原因码翻译成“补 classpath / 导入运行时”等动作。
- TraceBoundary — 作用：查询结果中的开放边界；场景：`trace` 输出“为什么不确定、怎么补”。
- TraceWeakHop — 作用：证据不足的跨调用跳；场景：`--max-weak-hops` 预算判定。
- AmbiguousCandidate — 作用：重载消歧失败的候选集；场景：报告 OVERLOAD_AMBIGUOUS。
- TraceEvidenceIssue — 作用：证据缺失/冲突诊断；场景：结果附带的诊断区。
  model/ 图数据（8）
- DataEdgeRecord — 作用：字段值数据边；场景：`value_data_edge` 表一行、遍历的基本单位。
- ValueNodeRecord — 作用：值节点（字段/参数/局部变量/返回值）；场景：图的顶点、目标解析。
- CallEdgeRecord — 作用：方法调用边；场景：跨方法追踪、可达范围计算。
- PendingRefRecord — 作用：暂未解析的引用；场景：`pending_parse_invoke_ref` 表，后续补证时回填。
- SymbolRecord — 作用：类/方法/字段符号；场景：`methods` 搜索、索引主表。
- MethodSummaryRecord — 作用：方法级摘要；场景：方法检索与概览展示。
- ParameterMutationRecord — 作用：参数被修改的记录；场景：调用值流判断实参是否被写回。
- SourceFileRecord — 作用：源文件元数据与 hash；场景：增量判定、快照一致性校验。
  model/ 查询结果（9）
- TraceResult — 作用：一次 trace 的完整结果；场景：`trace` 命令输出与 --fail-on-candidate 判定。
- TraceNode / TraceEdge — 作用：结果图节点与边；场景：JSON 结构化输出。
- TraceChain / TraceChainHop — 作用：组装后的可读链路与单跳；场景：text 输出的链路展示。
- TraceLeaf — 作用：链路端点；场景：标记“从哪开始/到哪结束”。
- TraceFileEvidence — 作用：文件与行号证据；场景：`source` 命令定位源码。
- TraceNoMatchReason — 作用：未命中原因；场景：目标不存在时给出可行动解释。
- TraceEdgeCategory — 作用：边分类；场景：区分字段流、控制依赖、调用输入。
  model/ 状态与质量（4）
- IndexStatusResult — 作用：`status` 返回结构；场景：查看索引新鲜度、代数、文件数。
- IndexQuality — 作用：质量指标聚合；场景：`status --quality`、边界密度门禁。
- SymbolStatistics — 作用：符号维度统计；场景：`status` 展示类/方法/字段数量。
- TargetStatistics — 作用：目标维度统计；场景：`targets` 展示候选与置信分布。
  model/ 命令与通用（14）
- CommandResult — 作用：所有命令的统一外壳；场景：text/JSON 渲染、成功失败包装。
- AnalysisResult — 作用：一次源码分析产物；场景：分析器返回给索引服务。
- Diagnostic — 作用：诊断信息与阻断标记；场景：解析失败、classpath 截断、索引中止。
- SourceResult / SourceEvidence — 作用：源码证据查询结果；场景：`source --edge` 定位到文件行。
- MethodsResult / MethodCandidate — 作用：方法搜索结果；场景：`methods --name`。
- TargetsResult / TargetCandidate — 作用：方法内可追踪目标；场景：`targets --method`。
- DoctorResult — 作用：环境体检结果；场景：`doctor` 检查 JDK/权限/配置。
- EvidenceImportResult / EvidenceDiffResult / EvidenceDiffItem — 作用：证据导入与差异；场景：`evidence import/diff`。
- SourceScanOptions — 作用：扫描选项；场景：语言级别、测试、生成源码、classpath 的统一传递。
  port/（9，全项目骨架）
- SourceAnalyzer — 作用：源码分析抽象；场景：`ProjectIndexService` 调它，实现是 JavaSourceAnalyzer。
- ProjectSourceReader — 作用：源文件采集抽象；场景：`index` 前后各采集一次做快照校验。
- IndexRepository — 作用：索引读写总入口；场景：CLI 打开数据库。
- IndexRepositoryFactory — 作用：仓库工厂；场景：按项目路径创建 SQLite 仓库。
- IndexPublisher — 作用：原子发布抽象；场景：一次索引结束时提交新 generation。
- IndexStateReader — 作用：状态读取抽象；场景：`status`、增量判定。
- IndexAttemptRecorder — 作用：索引尝试记录；场景：失败时留下 INDEX_TIMEOUT 等记录。
- GraphSnapshotReader — 作用：只读快照抽象；场景：`trace` 打开稳定视图。
- GraphReadSession — 作用：快照内会话；场景：一次查询内复用连接与缓存。
  query/（23）
- LineageIndex — 作用：内存图索引；场景：遍历器从这里取邻接边。
- LineageGraph — 作用：图结构与邻接封装；场景：正反向遍历。
- LineageQuery — 作用：查询参数对象；场景：方向、范围、预算、目标表达式。
- QuerySnapshotContext — 作用：快照+图+预算上下文；场景：一次查询的生命周期。
- ForwardLineageTraversal / BackwardLineageTraversal — 作用：正向/反向遍历；场景：`--direction out/in`。
- BidirectionalLineageTracer — 作用：双向调度与合并；场景：`--direction both` 默认路径。
- CallArgumentBinder — 作用：实参绑定形参；场景：跨方法追踪时接续值流。
- CallContextDirection — 作用：跨调用方向；场景：进入被调方或回到调用方。
- CallScopeMode — 作用：`effective/full` 范围；场景：`--call-scope`。
- LambdaElementAliasResolver — 作用：Lambda 参数别名解析；场景：`list.forEach(x -> ...)` 中 x 追溯到元素类型。
- TargetCandidateResolver — 作用：目标表达式解析；场景：`--target result.status` 找到起点。
- ReachableScopeCollector — 作用：收集可达方法；场景：`targets` 与范围裁剪。
- LineageChainAssembler — 作用：边组装成链路；场景：text 输出“A→B→C”。
- LineageResultMerger — 作用：正反向结果合并去重；场景：`both` 模式。
- Step / ValueRef / ValuePath — 作用：遍历步、值引用、路径；场景：路径记录与结果构造。
- TraversalState / TraversalFrontier / TraversalBudget — 作用：访问状态、前沿队列、预算；场景：BFS 与截断控制。
- TraceBudgetPolicy — 作用：`-1` 默认预算策略；场景：未显式指定深度/边数时。
- TraceQuality — 作用：通过/部分/不足/未知判定；场景：结果顶部的质量结论。
- shared/hash/Sha256Digest — 作用：SHA-256；场景：文件 hash、上下文指纹。
- shared/text/LineRange — 作用：行号区间；场景：源码证据定位。
- support/Ids — 作用：稳定 ID；场景：边/符号/项目指纹生成。
  lineage-java — 源码分析（81 个）

根包（7）
- JavaSourceAnalyzer — 作用：分析总调度；场景：`ProjectIndexService` 调用，串起解析到提取。
- JavaParserFactory — 作用：按语言级别建解析器；场景：`--language-level` 生效处。
- JavaAnalysisAccumulator — 作用：收集符号、边、诊断；场景：所有提取器写入的统一出口。
- CallableScope — 作用：方法/构造器/Lambda 作用域；场景：判断边属于哪个方法。
- JavaExpressionIdentity / JavaSymbolIdentity — 作用：表达式/符号稳定标识；场景：去重、跨文件引用。
- JavaTypeKind — 作用：类型种类判定；场景：区分类/接口/枚举/记录。
  extractor/（23）
- ValueNodeExtractor — 作用：生成值节点；场景：遍历 AST 建图顶点。
- ValueNodeCatalog — 作用：值节点注册表；场景：按表达式/字段查已有节点。
- ValueEdgeEmitter — 作用：统一发射数据边；场景：所有提取器标注 resolution/provenance 后写边。
- ExpressionFlowSources — 作用：从表达式找值来源；场景：赋值右侧、返回语句。
- SymbolRoleClassifier — 作用：区分字段/局部变量/参数；场景：决定边的端点类型。
- AccessorFlowExtractor — 作用：getter/setter 字段流；场景：`getX()/setX()` 传播。
- AssignmentFlowExtractor — 作用：赋值边；场景：`a = b`。
- SequentialFlowExtractor — 作用：顺序语句与变量覆盖；场景：同一变量多次赋值。
- ConstructorFieldFlowExtractor — 作用：构造器参数写字段；场景：`this.x = x`。
- CallSiteExtractor — 作用：调用点提取；场景：生成 parsed_invoke_edge。
- CallValueFlowExtractor — 作用：实参→形参、返回值→调用方；场景：跨方法值流。
- CallableScopeExtractor — 作用：构建作用域树；场景：嵌套类/Lambda 归属。
- BeanCopyExtractor — 作用：属性拷贝；场景：`BeanUtils.copyProperties`。
- BuilderFlowExtractor — 作用：Builder 链；场景：`builder.x(v).build()`。
- ContainerFlowExtractor — 作用：集合 add/put/get；场景：`map.put(k,v)` 的值流。
- ContainerFactoryFlowExtractor — 作用：容器工厂方法；场景：`List.of`、`Arrays.asList`。
- ContainerSerializationFlowExtractor — 作用：容器序列化字段暴露；场景：返回 List<VO> 时的字段投影。
- StreamFlowExtractor — 作用：Stream 链；场景：`map/filter/collect`。
- LambdaParameterFlowExtractor — 作用：Lambda 参数推断；场景：隐式类型参数追溯到元素。
- MethodReferenceExtractor — 作用：方法引用；场景：`User::getName`。
- MapStructMappingExtractor — 作用：MapStruct 映射；场景：`@Mapping`、`qualifiedByName`。
- BeanSerializationFlowExtractor — 作用：序列化字段输出；场景：Jackson/Fastjson/Gson，产出候选+框架适配证据。
- MethodSummaryExtractor — 作用：方法摘要；场景：`methods` 列表与概览。
  flow/（4）
- JavaValueFlowContext — 作用：提取器共享上下文；场景：类型、作用域、别名查询。
- GuardConditionResolver — 作用：条件对值流影响；场景：if/三元/短路。
- CallableOwnership / NodeFlowOwnership — 作用：调用者与节点归属；场景：确定边属于哪个方法、跨类归属。
  mybatis/（4）
- MyBatisMapperXmlParser — 作用：解析 Mapper XML；场景：`<select>/<if>/<foreach>`。
- MyBatisMapperDefinition — 作用：语句定义记录；场景：参数与结果映射。
- MyBatisParameterResolver — 作用：`#{}` 到 Java 字段；场景：Mapper 接口参数绑定。
- MyBatisFieldLineageExtractor — 作用：生成 MyBatis 字段流；场景：动态 SQL 记候选，等运行时 SQL 证据。
  project/（11）
- ClasspathManifestReader — 作用：读 classpath 清单；场景：`--classpath-file`。
- ClasspathEntryValidator — 作用：校验/规范化条目；场景：相对路径、Windows 盘符、去重。
- ClasspathTypeIndex — 作用：JAR/class 目录类型索引；场景：补全外部类型成员。
- ClassFileReader — 作用：直接读 class 元数据；场景：不加载类即可确认字段/方法。
- ClassFileMetadata / ClasspathEntry / ClasspathManifest / ValidatedClasspath — 作用：对应数据记录；场景：索引构建与指纹计算。
- JavaLanguageLevelResolver — 作用：推断语言级别；场景：多模块不同 Java 版本。
- JavaModuleCatalog — 作用：识别模块边界；场景：多模块项目路径与包归属。
- LanguageLevelResolution — 作用：推断结果（含是否假设）；场景：`doctor` 与诊断输出。
  resolution/（13）
- MethodCallResolver — 作用：调用解析与重载消歧；场景：`parsed_invoke_edge` 生成。
- CallResolution — 作用：解析结果记录；场景：调用点→目标方法。
- ReceiverTypeResolver — 作用：接收者类型；场景：`a.b().c()` 链式调用。
- ExpressionTypeResolver — 作用：一般表达式类型；场景：泛型/数组/装箱。
- TypeArgumentResolver — 作用：泛型实参推断；场景：`List<User>` 元素类型。
- JavaTypeNameResolver — 作用：类型名规范化；场景：内部类、全限定名、导入。
- JavaValueTypes — 作用：值类型工具；场景：基本类型与包装类型换算。
- AccessorResolver / AccessorFieldIdentity — 作用：访问器到字段映射；场景：getter/setter 归一。
- BuilderChainClassifier — 作用：识别 Builder 链；场景：避免把链式调用当普通方法。
- ExternalConstructorFieldCatalog — 作用：外部构造器字段目录；场景：三方类字段写入。
- SourceTypeCatalog — 作用：源码类型目录；场景：按全限定名查类。
- NonLineageCallFilter — 作用：过滤无关调用；场景：日志、`toString`、`equals`。
  semantic/（18）
- SemanticContextFingerprint — 作用：语义上下文指纹与规则版本；场景：判断缓存/索引是否失效。
- SemanticAnalysisCache — 作用：调用归属缓存；场景：复用 javac 结果。
- javac/JavacSemanticAnalyzer — 作用：真实类型归属；场景：重载、泛型、继承消歧。
- javac/SemanticAnalysisResult — 作用：语义分析产物；场景：回传归属与诊断。
- protocol/SemanticWorkerRequest / Response / CallAttribution / SemanticSource / SemanticWorkerDiagnostic — 作用：独立进程协议；场景：主进程与 javac worker 通信。
- worker/SemanticWorkerMain / SemanticWorkerLauncher — 作用：隔离 JVM 跑 javac；场景：避免污染主进程、控制内存。
- snapshot/ClasspathSnapshot / ClasspathSnapshotPreparer — 作用：固定 classpath 快照；场景：保证同一次分析可重复。
- source/CompilationSourceCollector / CompilationSourceSet — 作用：收集待编译源码；场景：喂给 javac worker。
- source/SourcePackageIndex / SourcePackageIndexBuilder — 作用：包索引；场景：同包类解析。
- source/SourceSnapshotVerifier — 作用：分析前后一致性校验；场景：索引期间源码被改动时中止。
  io/SafeXmlReader — 作用：防 XXE 的 XML 读取；场景：解析 MyBatis Mapper。

lineage-storage-sqlite（17 + 1 SQL）
- SqliteRepository — 作用：仓库实现总入口；场景：CLI 每次打开索引。
- SqliteRepositoryFactory — 作用：创建仓库；场景：按 trace.db 路径实例化。
- SqliteDataSourceFactory — 作用：连接与 PRAGMA 配置；场景：WAL、busy timeout、只读模式。
- SqliteSchemaMigrator — 作用：建表与在线迁移；场景：首次运行、升级表结构。
- SqliteWriteTransaction — 作用：写事务封装；场景：发布、导入证据。
- SqliteIndexPublisher — 作用：原子发布一个 generation；场景：`index` 最后一步。
- SqliteIndexAttemptRecorder — 作用：记录索引尝试；场景：失败/超时留痕。
- SqliteIndexStateReader — 作用：读状态与质量；场景：`status`、增量判定。
- SqliteGraphSnapshotReader — 作用：打开只读快照；场景：`trace --no-sync`。
- SqliteGraphReadSession — 作用：会话内查询；场景：一次 trace 复用连接。
- dao/SymbolDao — 作用：符号表读写；场景：索引发布与 methods 查询。
- dao/ValueGraphDao — 作用：值节点与数据边读写；场景：图构建与遍历。
- dao/CallEdgeDao — 作用：调用边读写；场景：跨方法追踪。
- dao/EdgeEvidenceDao — 作用：多证据表读写；场景：证据导入、聚合展示。
- dao/SourceFileDao — 作用：源文件表读写；场景：增量判定、文件证据。
- dao/MethodSummaryDao — 作用：方法摘要读写；场景：方法搜索。
- mapping/GraphRowMapper — 作用：行到记录映射；场景：所有查询结果装配。
- db/migration/V001__init.sql — 作用：全部建表/索引/外键；场景：首次建库与迁移基线。
  lineage-cli（52 + 1 properties）

bootstrap/
- FieldTraceGraphApplication — 作用：唯一 main；场景：所有命令的进程入口。
  config/
- LineageBeanConfiguration — 作用：手工装配对象链与命令树；场景：启动时。
- LineageRuntimeProperties — 作用：运行参数记录；场景：深度、预算、超时默认值。
- ToolConfigurationLoader — 作用：读 properties/环境变量/系统属性；场景：覆盖默认配置。
- SourceScanOptionsFactory — 作用：命令行参数转扫描选项；场景：`index/trace/targets/...` 共用。
  controller/（12）
- RootCommand — 作用：根命令与帮助；场景：`ftg`、`ftg --help`。
- InitCommand — 作用：初始化项目；场景：首次使用。
- IndexCommand — 作用：建/刷新索引；场景：`ftg index`。
- StatusCommand — 作用：状态与质量门禁；场景：`ftg status --quality`。
- MethodsCommand — 作用：方法搜索；场景：`ftg methods --name X`。
- TargetsCommand — 作用：列目标；场景：`ftg targets --method ...`。
- TraceCommand — 作用：血缘追踪与门禁；场景：`ftg trace ...`。
- SourceCommand — 作用：源码证据；场景：`ftg source --edge ...`。
- DoctorCommand — 作用：环境体检；场景：`ftg doctor`。
- EvidenceCommand — 作用：证据子命令容器；场景：`ftg evidence ...`。
- EvidenceImportCommand — 作用：导入运行时证据；场景：`ftg evidence import`。
- EvidenceDiffCommand — 作用：证据差异；场景：`ftg evidence diff`。
  application/（13）
- ProjectIndexService — 作用：索引主流程；场景：`index/init`、查询前自动同步。
- ProjectInitializationService — 作用：`init` 编排；场景：建目录+首次索引。
- IndexReadinessService — 作用：查询前确保索引可用；场景：`methods/targets/trace` 自动同步。
- IndexStatusService — 作用：组装状态与质量；场景：`status`。
- TraceApplicationService — 作用：trace 编排与门禁；场景：同步、stale、退出码。
- LineageQueryService — 作用：打开快照并执行追踪；场景：trace 的核心查询。
- MethodSearchService — 作用：方法搜索；场景：`methods`。
- TargetDiscoveryService — 作用：目标发现；场景：`targets`。
- SourceEvidenceService — 作用：源码证据查询；场景：`source`。
- EvidenceImportService — 作用：证据落库；场景：`evidence import`。
- EvidenceDiffService — 作用：证据差异计算；场景：`evidence diff`。
- EvidenceFileReader — 作用：解析规范 JSON/OTLP/JFR；场景：导入前的格式适配。
- RuntimeDoctorService — 作用：运行环境检查；场景：`doctor`。
  project/（8）
- ProjectRootResolver — 作用：向上找项目根；场景：未传 --project。
- SourceFileScanner — 作用：遍历源码目录；场景：建索引。
- SourcePathPolicy — 作用：包含/排除规则；场景：测试目录、构建目录过滤。
- GitIgnoreRules — 作用：解析 .gitignore；场景：跳过忽略文件。
- SourceFileReader — 作用：读文件并算 hash；场景：增量判定。
- LocalProjectSourceReader — 作用：本地源码采集实现；场景：`index` 的输入。
- ProjectIndexPaths — 作用：`.field-trace-graph` 路径常量；场景：数据库、锁、契约文件定位。
- ProjectWriteLock — 作用：文件写锁；场景：并发 index 串行化。
  contract/（3）
- ProjectContractLoader — 作用：读 contracts.yml；场景：人工契约、生成源码根、边界声明。
- ProjectContracts — 作用：契约集合；场景：索引与查询时补充证据。
- ManualContractEvidence — 作用：人工契约转证据；场景：`MANUAL_CONTRACT` 来源。
  view/（3）
- ResultView — 作用：输出门面；场景：按 --format 分发。
- TextResultView — 作用：人类可读输出；场景：默认终端输出、诊断追加。
- JsonResultView — 作用：稳定 JSON；场景：CI、下游消费。
  dto/（3）
- CommandResultDto — 作用：命令 JSON 外壳；场景：`--format json`。
- TraceResultDto — 作用：trace JSON 结构；场景：CI 解析边与证据。
- EvidenceImportItem — 作用：导入项结构；场景：`evidence import` 输入。
  error/（4）
- ExitCode — 作用：退出码常量；场景：脚本判断成功/质量门禁失败。
- CliFailure — 作用：业务异常；场景：命令内主动失败。
- CliExceptionMapper — 作用：异常到退出码映射；场景：统一错误出口。
- CliExecutionExceptionHandler — 作用：picocli 异常处理；场景：参数错误、未捕获异常。
  log/CliLog — 作用：日志走 stderr；场景：保证 stdout 的 JSON 干净。

resources/field-trace-graph.properties — 作用：默认配置；场景：深度、预算、超时的出厂值。

distribution — 发布与供应链（20）
- build-release.sh — 作用：构建单平台归档；场景：本地或 CI 打某平台包。
- verify-release.sh — 作用：发布门禁总校验；场景：发布前最后一道关。
- smoke-test-release.sh — 作用：归档冒烟；场景：验证解压后能跑。
- smoke-test-npm.sh — 作用：npm 包冒烟；场景：验证 npx 安装路径。
- security-scan.sh — 作用：gitleaks+syft 扫描；场景：CI 安全门禁。
- update-release-manifest.js — 作用：写清单 URL/hash；场景：构建完归档后。
- release-manifest.json — 作用：三平台版本与 SHA-256；场景：npm 安装器下载。
- launcher/field-trace-graph — 作用：Unix 启动器；场景：归档内启动。
- launcher/field-trace-graph.bat — 作用：Windows 启动器；场景：Windows 归档。
- npm/package.json — 作用：npm 包定义与 bin 别名；场景：`npm i -g`、`npx`。
- npm/bin/field-trace-graph.js — 作用：npm 入口脚本；场景：执行 ftg。
- npm/lib/release-downloader.js — 作用：按平台下载归档；场景：首次安装/升级。
- npm/lib/archive-installer.js — 作用：解压安装；场景：下载后落盘。
- npm/lib/platform-resolver.js — 作用：识别 OS/CPU；场景：选择归档。
- npm/scripts/sync-manifest.js — 作用：打包前同步清单；场景：`npm pack` 前。
- npm/release-manifest.json — 作用：包内清单副本；场景：安装时读取。
- licenses/third-party/Apache-2.0.txt — 作用：Apache 许可原文；场景：归档合规。
- licenses/third-party/jackson-NOTICE.txt — 作用：Jackson NOTICE；场景：归档合规。
- licenses/third-party/sqlite-jdbc-LICENSE.txt — 作用：SQLite JDBC 许可；场景：归档合规。
- licenses/third-party/sqlite-jdbc-LICENSE.zentus.txt — 作用：Zentus 原始许可；场景：归档合规。
  两个资源文件
- lineage-cli/src/main/resources/field-trace-graph.properties — 作用：默认运行参数；场景：未用命令行覆盖时。
- lineage-storage-sqlite/src/main/resources/db/migration/V001__init.sql — 作用：建库脚本；场景：首次 index 或迁移。





- lineage-core/pom.xml — 作用：core 模块构建定义，没有任何第三方依赖；场景：验证“内核不依赖框架”的架构约束，加依赖前要想清楚。
- lineage-java/pom.xml — 作用：声明依赖 lineage-core、JavaParser、Jackson；场景：升级解析器版本、加分析相关依赖。
- lineage-storage-sqlite/pom.xml — 作用：声明 lineage-core、`sqlite-jdbc`、Jackson；场景：换 SQLite 驱动、调存储依赖。
- lineage-cli/pom.xml — 作用：声明三个内部模块 + picocli，并配置 shade 插件打可执行 JAR（finalName=field-trace-graph、`Main-Class=FieldTraceGraphApplication`、剔除签名文件）；场景：改打包方式、换入口类、排查 JAR 启动失败。
- distribution/build/ — 作用：`build-release.sh` 的产物目录（当前未生成）；场景：需要验收归档时执行构建，用完可整目录删除。
- .field-trace-graph/index.lock — 作用：写锁文件；场景：并发 index 时串行化，进程异常退出后可能是空文件，可安全删除。
- .field-trace-graph/trace.db — 作用：SQLite 索引主库（当前 63MB）；场景：`trace --no-sync` 直接读它，删掉需重新 index。
- .field-trace-graph/trace.db-shm / trace.db-wal — 作用：SQLite 共享内存与预写日志；场景：WAL 模式下正常存在，不要手工编辑。
- security-reports/gitleaks-source-latest.json — 作用：源码扫描结果，内容为 [] 表示无发现；场景：发布审计。
- security-reports/gitleaks-release-archive-latest.json — 作用：归档扫描结果（同样 []）；场景：发布审计。
- security-reports/gitleaks-npm-package-latest.json — 作用：npm 包扫描结果（同样 []）；场景：发布审计。
- .idea/*（8 个：`compiler.xml`、`encodings.xml`、`jarRepositories.xml`、`misc.xml`、`vcs.xml`、`workspace.xml`、`JpiProjectStorage.xml` 等）— 作用：IntelliJ 本地配置；场景：只影响你本机 IDE，已被忽略，不要提交。
- distribution/npm/node_modules/ — 作用：npm 依赖目录；场景：当前不存在，执行 npm install 后才生成，打包时被排除。






二、按“使用场景”反查文件（补场景维度）
- 新增一条 CLI 命令：`controller/XxxCommand` → 在 LineageBeanConfiguration 注册 → application/XxxService → 需要输出就加 dto/ 或在 view/ 扩展 → 更新 README.md。
- 调整置信度或证据规则：`Resolution`、`Provenance`、`EdgeEvidence`、`BoundaryReason` → extractor/ValueEdgeEmitter（产出侧）→ SqliteIndexPublisher + dao/EdgeEvidenceDao（落库）→ IndexQuality / TraceQuality（统计）→ 提升 SemanticContextFingerprint.RULE_VERSION → 用真实项目回放自测。
- 新增框架适配器（如 Dubbo、gRPC）：在 extractor/ 新建提取器 → JavaSourceAnalyzer 里注册 → 选择或新增 BoundaryReason → 只输出 CANDIDATE + FRAMEWORK_ADAPTER → 用真实项目回放自测。
- 改数据库表结构：`V001__init.sql` → SqliteSchemaMigrator（迁移与重建）→ 对应 dao/*Dao → GraphRowMapper → 用旧库与并发场景自测。
- 改查询/遍历算法：`query/ForwardLineageTraversal`、`BackwardLineageTraversal`、`BidirectionalLineageTracer` → TraversalBudget / TraceBudgetPolicy → TraceQuality → 用端到端 trace 自测。
- trace 结果不对时排查：先 status --quality 看边界密度 → source --edge 定位源码 → 看 reasonCode 决定补 classpath / 生成源码 / 运行时证据 → 对应 resolution/ 或 extractor/。
- 支持新构建工具或 classpath 来源：`project/ClasspathManifestReader`、`ClasspathEntryValidator`、`JavaModuleCatalog`、`SourcePathPolicy`。
- 改输出格式：`view/TextResultView`、`view/JsonResultView`、`dto/CommandResultDto`、`dto/TraceResultDto`。
- 改增量索引策略：`IndexChangePlanner`、`AffectedScopeResolver`、`SemanticContextFingerprint`、`SourceSnapshotVerifier`。
- 发版：`To-do/RELEASE-CHECKLIST.md` → build-release.sh → update-release-manifest.js → security-scan.sh → verify-release.sh → smoke-test-*.sh → .github/workflows/release.yml → npm 包。
- 改运行默认值：`field-trace-graph.properties` → ToolConfigurationLoader → LineageRuntimeProperties。
- 

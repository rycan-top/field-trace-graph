<div align="center">

# field-trace-graph

**Trace any Java field back to where it comes from — one command, fully local.**

`ftg` builds a field-level lineage graph of a local Java project and answers
"where does this value come from, and where does it flow to?" — no server, no
upload, no model, no background process.

[![npm version](https://img.shields.io/npm/v/field-trace-graph.svg)](https://www.npmjs.com/package/field-trace-graph)
[![License: Apache-2.0](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](https://opensource.org/licenses/Apache-2.0)
[![Node.js](https://img.shields.io/badge/Node.js-18%2B-brightgreen.svg)](https://nodejs.org/)
[![Platforms](https://img.shields.io/badge/platforms-macOS%20%7C%20Linux%20%7C%20Windows-blue.svg)](#supported-platforms)

```bash
npm install -g field-trace-graph && ftg --version
```

[Get Started](#get-started) · [Key features](#key-features) · [Commands](#command-reference) · [Troubleshooting](#troubleshooting)

</div>

## Contents

- [Get Started](#get-started)
- [Why field-trace-graph?](#why-field-trace-graph)
- [Key features](#key-features)
- [Supported platforms](#supported-platforms)
- [Command Reference](#command-reference)
- [Environment Variables](#environment-variables)
- [Semantic analysis and isolation](#semantic-analysis-and-isolation)
- [Generic types and framework adapters](#generic-types-and-framework-adapters)
- [Project detection and language level](#project-detection-and-language-level)
- [Field-lineage semantic contract](#field-lineage-semantic-contract)
- [External classpath](#external-classpath)
- [Index location](#index-location)
- [Output](#output)
- [Troubleshooting](#troubleshooting)
- [Development](#development)
- [Release](#release)
- [License](#license)

## Get Started

### 1. Install

```bash
npm install -g field-trace-graph
```

Node.js 18 or newer; no JDK is required, each release bundles its own runtime.
The package installs two names for the same command, `field-trace-graph` and the
short `ftg` — the examples below use `ftg`.

On first run — including a bare `ftg --version`, because the launcher needs the
release before it can forward anything — the launcher resolves the archive for
your OS/CPU from the bundled release manifest, verifies its SHA-256, extracts it
under `~/.field-trace-graph/dist/<version>`, and forwards arguments, signals,
and exit codes to that executable. The first download is about 200 MB; later
runs reuse the cache and start in under a second.
```bash
# Pin a version (what CI should do), or upgrade and remove.
npm install -g field-trace-graph@0.1.0
npm install -g field-trace-graph@latest
npm uninstall -g field-trace-graph

# Drop downloaded archives and runtimes; they are fetched again on demand.
rm -rf ~/.field-trace-graph/dist
```

<details>
<summary><b>Without npm — platform archive or a local JAR</b></summary>

Air-gapped machines and release debugging can skip the launcher entirely:

```bash
# Published archive: no JDK and no Node.js are needed after extraction.
tar -xzf field-trace-graph-0.1.0-linux-x64.tar.gz
./bin/ftg --version

# Or build the shaded JAR and run it with a local JDK (see Development).
java -jar lineage-cli/target/field-trace-graph.jar --version
```

</details>

### 2. Index a project

```bash
cd your-java-project
ftg init
```

`init` builds the complete graph in one pass and writes it to
`.field-trace-graph/trace.db` inside the project. It never uploads source code.

### 3. Trace a field

Run the commands from the Java project being analyzed. If the current directory
is not the project root, pass `--project /path/to/project`.

```bash
# Find a method. The output contains the signature to copy into the next command.
ftg methods --name OrderService

# Inspect the value targets in a method.
ftg targets --method 'com.example.OrderService#create(String,String)'

# Trace a specific value target in both directions.
ftg trace \
  --method 'com.example.OrderService#create(String,String)' \
  --target result.status \
  --direction both

# Include callers when investigating how the field reaches invoking code.
ftg trace \
  --method 'com.example.OrderService#create(String,String)' \
  --target result.status \
  --direction both \
  --call-scope full
```

Every command prints text by default; add `--format json` for a single JSON
object on stdout. Progress and logs always go to stderr.

## Why field-trace-graph?

Grep tells you where a name appears. It does not tell you how a value actually
reaches a field — through getters and setters, builders, BeanCopy, MapStruct,
streams, MyBatis parameter bridging, or a helper two calls away. Reconstructing
that by hand means opening file after file and re-deriving the same paths every
time.

`ftg` builds the graph once and answers directly: given a method and a target
expression, it returns the field-level edges in both directions, each carrying
the file and line that produced it, plus an explicit boundary when resolution
stops. Every hop is either evidence-backed (`EXACT_STATIC`) or clearly marked as
a candidate, so you can see why a conclusion was reached instead of trusting a
black box.

Everything runs locally against a SQLite index in the project — no server, no
network call, no source upload.

## Key features

| | |
|---|---|
| **Field-level lineage** | Traces individual fields and access paths (`result.status`, `goodTabCoupons[*].coupons`), not just method calls |
| **Bidirectional** | `--direction in` asks who writes a value, `out` asks where it flows, `both` joins the two at the target |
| **Evidence per edge** | Every edge records `fileId`, line/column range, resolution kind, and provenance; `ftg source` reprints the exact lines |
| **Explicit boundaries** | Unresolved flows end at a named reason (`CLASSPATH_MISSING`, `GENERATED_CODE_MISSING`, `RPC_BOUNDARY`, …) with a suggested next action instead of a silent gap |
| **Quality gates for CI** | `--fail-on-candidate`, `--fail-on-reason`, `--fail-on-boundary-density`, `--max-weak-hops` return exit code `6` when the graph is too weak |
| **Stable JSON output** | `--format json` emits one versioned object on stdout; logs and progress always go to stderr |
| **Framework-aware** | JavaParser-based extraction covers streams, builders, BeanCopy, MapStruct, Lombok-generated members, MyBatis Mapper XML, and constructor/super bindings |
| **100% local** | SQLite index in `.field-trace-graph/trace.db`; nothing leaves the machine |

## Supported platforms

| Platform | Architecture | Archive |
|---|---|---|
| macOS | arm64 | `field-trace-graph-<version>-darwin-arm64.tar.gz` |
| macOS | x64 | `field-trace-graph-<version>-darwin-x64.tar.gz` |
| Linux | x64 | `field-trace-graph-<version>-linux-x64.tar.gz` |
| Windows | x64 | `field-trace-graph-<version>-win32-x64.zip` |

Each archive bundles a JDK 17 runtime, so end users install no JDK.

## Command Reference

All normal commands accept `--project PATH` unless noted. The default project
root is detected from the current directory by walking up to the nearest Git
worktree, `pom.xml`, `build.gradle`, or `build.gradle.kts`.

### Common behavior

- `--format text` is the default. `--format json` emits one JSON object on
  standard output. Logs and progress use standard error.
- `--language-level N` overrides the language level detected from each module.
- `--include-tests` includes test source roots. Tests are excluded by default.
- `methods`, `targets`, and `trace` normally synchronize the local index before
  querying it. `--no-sync` reads the existing graph without updating it.
- `trace --allow-stale` permits reading the previous published generation when a
  refresh fails.
- `init` and `index` use `.field-trace-graph/index.lock` to serialize writers.
- `--generated-sources PATH` adds explicit annotation-processor or generated
  source roots. The option is repeatable or comma-separated and never guesses a
  build layout. Edges whose evidence file is under one of these roots are
  published with `provenance=GENERATED_SOURCE`.
- `--classpath PATH` adds an explicit JAR or class directory. It is repeatable
  or comma-separated, participates in the index context fingerprint, and is
  never inferred from `target/classes`. Class-file metadata is read without
  loading or executing dependency code.
- `--classpath-file PATH` reads classpath entries from a file produced by
  `mvn dependency:build-classpath` or a Gradle task. Entries may be one per
  line or separated by `:`/`;`; relative entries resolve against the file's
  directory. The file is merged with repeated `--classpath` options.

### Evidence and quality

Each logical edge has an independent `resolution` and `provenance`:

```text
resolution: EXACT_STATIC / CANDIDATE / UNRESOLVED / RUNTIME_OBSERVED
provenance: SOURCE / GENERATED_SOURCE / BYTECODE / FRAMEWORK_ADAPTER /
            RUNTIME_TRACE / MANUAL_CONTRACT
```

`edge_evidence` stores all evidence rows for a logical edge. Static source,
framework adapters, generated source, and future runtime imports therefore
accumulate instead of overwriting one another. `trace --format json` exposes
the selected top-level `resolution`/`provenance` and the complete `evidence[]`
array on each edge.

Open boundaries are not treated as silent failure. Every boundary reports
`reasonCode`, `recoverable`, `suggestedAction`, and `evidenceSource`. Weak hops
and ambiguous overload candidates use the same fields. Typical actions are
`run mvn generate-sources`, `provide --generated-sources`,
`provide-classpath`, `import-runtime-trace`, or `import-runtime-sql`.

Boundary reasons are deliberately specific so the next evidence step is
obvious:

| `reasonCode` | Meaning | First action |
| --- | --- | --- |
| `CLASSPATH_MISSING` | The receiver type is named by source/imports but no supplied classpath entry confirms its members. | `provide --classpath` |
| `GENERATED_CODE_MISSING` | The member is synthetic or processor-generated (Lombok, enum `values`/`valueOf`). | `provide --generated-sources` or rebuild with generated sources |
| `CALLEE_SYMBOL_MISSING` | The owner type is in source but the referenced member is absent and not known to be generated. | reindex with generated sources or supply the owning module/classpath |
| `RECEIVER_TYPE_UNKNOWN` | The receiver expression itself cannot be typed, typically a chain rooted in an unresolved value. | trace the receiver expression or import a runtime trace |
| `RPC_BOUNDARY` | A Dubbo/gRPC/Thrift/CXF/Feign/JAX-WS-style remote call boundary. | provide a contract or import runtime evidence |
| `SERIALIZATION_BOUNDARY` | Fastjson/Jackson/Gson-style serializer or deserializer call. | import runtime evidence or add a contract |
| `RPC_SERIALIZATION` | Legacy combined code found in indexes built before the split. | reindex to obtain the specific code |
| `EXTERNAL_LIBRARY` | Class-file metadata confirms the external member. | trace into it with runtime evidence if field lineage matters |
| `PLATFORM_LIBRARY` | JDK/platform call. It is closed by default and does not count toward unknown-boundary density. | none |
| `OVERLOAD_AMBIGUOUS` | Several same-arity targets remain after type narrowing. | disambiguate with types or provide the owning classpath |

`EXTERNAL` remains only as a legacy fallback for calls whose receiver type
cannot even be named. New indexes should not accumulate it in normal use; the
specific reason is what quality reports and contracts should consume.

### Explicit project contracts

FTG never reads `application.yml` to infer lineage. A project may instead add
`.field-trace-graph/contracts.yml`:

```yaml
version: 1
generatedSources:
  - target/generated-sources/annotations
contracts:
  - from: source.orderStatus
    to: RETURN.status
    reason: "MapStruct qualifiedByName converter"
    owner: "order-team"
    expiresWhen: "Source.orderStatus is removed"
    confidence: EXACT_STATIC
```

Generated-source entries are treated exactly like repeated
`--generated-sources` options. A contract must contain `from`, `to`, `owner`,
`reason`, and `expiresWhen`; otherwise it is ignored with a warning. When
`expiresWhen` contains a calendar date (`YYYY-MM-DD`), an expired contract is
skipped with a warning. `trace --strict-contracts` turns an incomplete,
expired, or unmatched contract into a hard failure. FTG matches
the contract against a shortest discovered evidence path, so the approved
endpoints may be separated by an intermediate setter or assignment. Every
matched path edge receives an additive `MANUAL_CONTRACT` evidence row; source
evidence is never removed.

### Runtime evidence import

Runtime capture imports observations without rewriting the static graph:

```bash
field-trace-graph evidence import \
  --file runtime-evidence.json \
  --format json
```

```json
{
  "schemaVersion": 1,
  "evidence": [
    {
      "logicalEdgeKey": "... copy from trace edges[].evidence[].logicalEdgeKey ...",
      "evidenceKind": "METHOD_ARGUMENT",
      "adapter": "opentelemetry",
      "rule": "observed by checkout integration test",
      "coverage": "one execution of checkout-e2e",
      "observedAt": "2026-11-09T00:00:00Z",
      "details": {"traceId": "..."}
    }
  ]
}
```

Imported rows use `resolution=RUNTIME_OBSERVED` and
`provenance=RUNTIME_TRACE`. `coverage` is mandatory because a runtime hit
proves only the observed execution path, not every branch. The original static
or adapter evidence remains in `edge_evidence` and in the trace `evidence[]`
array.

The same command auto-detects OpenTelemetry OTLP JSON and JFR JSON exports.
Carry `ftg.logicalEdgeKey` and `ftg.coverage` as span attributes (directly, in
an OTLP `attributes` array, or in a JFR `values` object). Optional attributes
are `ftg.scopeSymbolId`, `ftg.evidenceId`, `ftg.sourceEdgeId`,
`ftg.evidenceKind`, `ftg.adapter`, and `ftg.rule`. `startTimeUnixNano` is
converted to `observedAt`. Attributes without `ftg.logicalEdgeKey` are ignored
rather than guessed.

Compare captured keys with the current static graph before importing:

```bash
field-trace-graph evidence diff \
  --file runtime-evidence.json \
  --fail-on-missing \
  --format json
```

`evidence diff` reports `observedCount`, `matchedCount`, `missingCount`, and
`matchRatio`. A missing key means the runtime capture and the published graph
disagree; it is not used to invent an edge. `--fail-on-missing` returns exit
code `3` with diagnostic `RUNTIME_EVIDENCE_DRIFT`, which makes it suitable for
CI drift checks.

### `field-trace-graph` and `--help`

```bash
field-trace-graph
field-trace-graph --help
field-trace-graph --version
```

With no subcommand, the command prints the application banner. `--help` prints
the top-level command list. `--version` prints the application and schema
version. These are useful for checking which binary is installed.

### `init`

```bash
field-trace-graph init [OPTIONS]
```

Initializes the project and performs a full local index. It creates
`.field-trace-graph/trace.db`, analyzes Java source and semantic calls, and
publishes the initial graph generation. The result can be `PARTIAL` when
semantic diagnostics remain. Run it once for a new checkout; use `index` for
later refreshes.

| Option | Default | Effect |
|---|---:|---|
| `--project PATH` | Current detected root | Project directory to index. |
| `--format text / json` | `text` | Result output format. |
| `--lock-timeout SECONDS` | `30` | How long to wait for the project write lock. |
| `--index-timeout SECONDS` | `300` | Maximum time allowed for the initial index. |
| `--language-level N` | Auto-detected | Force one Java language level for the analysis. |
| `--include-tests` | `false` | Index test source roots as well as main sources. |
| `--generated-sources PATH` | None | Explicit generated-source root; repeat or comma-separate. |
| `--classpath PATH` | None | Explicit JAR or class directory; repeat or comma-separate. |

### `index`

```bash
field-trace-graph index [OPTIONS]
```

Builds or refreshes the local graph. Without `--full`, the command is normally
incremental. Use `--full` when a complete rebuild is required, for example after
changing parser rules or compilation context.

| Option | Default | Effect |
|---|---:|---|
| `--project PATH` | Current detected root | Project directory to index. |
| `--full` | `false` | Force a full rebuild instead of an incremental update. |
| `--format text / json` | `text` | Result output format. |
| `--lock-timeout SECONDS` | `30` | How long to wait for the project write lock. |
| `--index-timeout SECONDS` | `300` | Maximum time allowed for one index attempt. |
| `--language-level N` | Auto-detected | Force one Java language level for the analysis. |
| `--include-tests` | `false` | Index test source roots as well as main sources. |
| `--generated-sources PATH` | None | Explicit generated-source root; repeat or comma-separate. |
| `--classpath PATH` | None | Explicit JAR or class directory; repeat or comma-separate. |

### `status`

```bash
field-trace-graph status [OPTIONS]
```

Reads the published index state without writing to the graph. The text output
shows generation, graph status, freshness, file/symbol/node/edge counts, and the
last failed attempt when one exists. Use this to verify that `init` or `index`
published what you expected.

| Option | Default | Effect |
|---|---:|---|
| `--project PATH` | Current detected root | Project whose index is inspected. |
| `--format text / json` | `text` | Result output format. |
| `--language-level N` | Auto-detected | Override language level used for the status check. |
| `--include-tests` | `false` | Use the test-inclusive source scope. |
| `--generated-sources PATH` | None | Explicit generated-source root used for freshness checks. |
| `--classpath PATH` | None | Explicit JAR or class directory used for freshness checks. |
| `--classpath-file PATH` | None | Classpath file merged into the freshness context. |
| `--quality` | `false` | Include exact/candidate/unresolved ratios, open boundaries, density per 1000 methods, and top boundary reasons. |
| `--fail-on-boundary-density N` | None | Return exit code `6` when open boundaries per 1000 methods exceed `N`. |
| `--fail-on-reason CODE` | None | Return exit code `6` when the canonical boundary reason is present; repeat or comma-separate. |

`openBoundaryCount` and `openBoundaryDensityPer1000Methods` exclude
`PLATFORM_LIBRARY` calls such as `String.valueOf` or `Long.parseLong`. Those are
known JDK boundaries, not unknown project evidence; they remain visible in
`trace` boundaries and weak hops.

`frameworkAdapterEdgeCount` / `frameworkAdapterRatio` separate candidates
produced deliberately by adapters (MapStruct, MyBatis, Jackson/Fastjson field
serialization) from genuinely ambiguous candidates. Use
`ambiguousCandidateEdgeCount()` / `ambiguousCandidateRatio()` when a release
gate should ignore additive adapter evidence.

Text output separates the symbol count into comparable buckets:

```text
types=... methods=... businessMethods=... accessors=... constructors=... initializers=...
```

`methods` includes declared business methods, accessors, and synthetic
initializers. `businessMethods` is the default `methods` search scope.
`accessors` counts getters and setters without removing their field-flow
evidence from the graph.

### `methods`

```bash
field-trace-graph methods --name NAME [OPTIONS]
```

Searches class, method, and constructor candidates by a name fragment. Text
output prints complete method signatures without internal IDs. Copy a signature
directly into `targets --method` or `trace --method`.

| Option | Default | Effect |
|---|---:|---|
| `--name NAME` | Required | Class or method name fragment. |
| `--project PATH` | Current detected root | Project to search. |
| `--format text / json` | `text` | Result output format. |
| `--offset N` | `0` | Number of matching candidates to skip. |
| `--limit N` | `30` | Maximum number of candidates to print. |
| `--no-sync` | `false` | Do not refresh the index before searching. |
| `--language-level N` | Auto-detected | Override language level if a refresh is needed. |
| `--include-tests` | `false` | Search test source roots as well as main sources. |
| `--include-accessors` | `false` | Include getter/setter symbols in search results. |
| `--classpath PATH` | None | Explicit JAR or class directory used if a refresh is needed. |

### `targets`

```bash
field-trace-graph targets --method SIGNATURE [OPTIONS]
```

Lists value expressions that can be passed to `trace --target`, together with
their kind and source location. The list includes method-scope values and
evidence-backed field paths discovered in callees, so a parameter such as
`agreementSignBO` also exposes `agreementSignBO.addressId`,
`agreementSignBO.originId`, and the other fields that actually participate in
lineage. The method selector should normally be the full signature printed by
`methods`; a stable method ID is also accepted for automation.

The summary separates field lineage from call-expression bookkeeping:

```text
Targets: 45 (comparable=37 fields=29 roots=7 returns=1 calls=8)
```

`comparable` is `fields + roots + returns`. `calls` contains
`CALL_RESULT`/`CONSTRUCTOR_RESULT` targets such as
`call:queryByAuctionId(1)`; they remain selectable but must not be counted as
object or field lineage when comparing graph implementations.

| Option | Default | Effect |
|---|---:|---|
| `--method SIGNATURE` | Required | Method signature or stable method ID. |
| `--project PATH` | Current detected root | Project to query. |
| `--format text / json` | `text` | Result output format. |
| `--offset N` | `0` | Number of matching targets to skip. |
| `--limit N` | `30` | Maximum number of targets to print. |
| `--no-sync` | `false` | Do not refresh the index before querying. |
| `--language-level N` | Auto-detected | Override language level if a refresh is needed. |
| `--include-tests` | `false` | Search test source roots as well as main sources. |
| `--classpath PATH` | None | Explicit JAR or class directory used if a refresh is needed. |

### `trace`

```bash
field-trace-graph trace \
  --method SIGNATURE \
  --target EXPRESSION \
  [OPTIONS]
```

Traces where a selected value or field comes from and where it flows. An object
target is evaluated as the union of its evidence-backed field traces, which
keeps object-level queries close to the real field lineage instead of walking
into every downstream object. Text output contains nodes, ordered chains,
data/call edges, boundaries, leaves, ambiguous overload candidates, weak hops,
and source evidence. Internal `PHI`/branch versions are kept in JSON but folded
out of the primary node list.

By default, `--call-scope effective` limits the loaded lineage to the selected
method and its callees. This keeps a field query focused on the field's real
producers and consumers instead of expanding through every unrelated caller.
Use `--call-scope full` only when diagnosing how a field reaches invoking code;
it also loads callers and may hit depth or edge limits on large call graphs.

`leaves` are the directional stopping points. `weakHops` are source-visible
calls that could not be bridged into analysed source, such as JDK or external
services; they are explicit boundaries and do not by themselves make the result
`PARTIAL`. Boundaries and leaves carry `evidenceId`, `sourceFile`, and
structured line/column ranges; weak hops carry `callSiteId`, `sourceFile`,
structured line/column ranges, `resolution=UNRESOLVED`, `reasonCode`,
`recoverable`, `suggestedAction`, and `evidenceSource`. An external call is
named with its declaring type, for example
`com.example.RemoteApi#fetch(java.lang.String)`, so it can be supplied to a
contract or runtime capture. `RPC_BOUNDARY` means a class-file or framework
annotation identified a remote call; `SERIALIZATION_BOUNDARY` means a
Fastjson/Jackson/Gson-style serializer or deserializer was called;
`CLASSPATH_MISSING` means the compiler saw the symbol but the supplied
classpath did not contain it. MyBatis SQL boundaries point at the mapper XML
file and bind expression, not at the mapper interface line.
`snapshotConsistent` confirms that every edge resolved to evidence
from the same published index generation. `fileIndexes` is the deduplicated
file-level evidence index for the trace. `evidenceIssues` reports missing or
hash-less evidence, and `noMatchReason` distinguishes a valid target with no
data edges from a field that does not exist.

Each edge also carries `operation`, `depth`, `resolved`, `bridged`, `srcScope`,
`dstScope`, `sourceFile`, `lineStart`, `lineEnd`, `argIndex`, and `argLevel`.
`operation` is the stored source expression when one is available; otherwise it
is a readable label for the edge type, so JSON consumers never need to infer a
step from a raw ID. Call-result producer hops use `CALL_PRODUCER` and expose the
receiver-to-result relationship, including external calls such as
`baseCommonService.getPersonDaiKouConfig(...)`.

Every edge also carries `category`:

- `FIELD_VALUE`: field or value lineage.
- `CALL_STRUCTURE`: call producer hops such as `CALL_PRODUCER`.
- `DEPENDENCY`: explicit control/call-input dependency edges included by
  `--include-dependencies`.
- `DIAGNOSTIC`: explanatory boundary or structure edges that do not
  participate in field-value quality.

`quality` is evaluated against field-value edges only. `structureEdges`,
`structureUnresolvedEdges`, and `structureStatus` report call producer gaps
separately; `diagnosticEdges`, `diagnosticUnresolvedEdges`, and
`diagnosticStatus` do the same for explanatory boundaries. Unresolved
structural or diagnostic edges stay visible without making complete field
lineage look incomplete. Only actual depth, edge, target-seed, or time limits
set `truncated`. Object target expansion is evaluated under the same query
deadline as traversal.

`chains[]` is a complete presentation projection over the authoritative
`edges[]`. Every chain has `edgeIds` and `hops[]`; each hop contains `edgeId`,
`srcNodeId`, `dstNodeId`, `edgeType`, `operation`, `resolution`, `resolved`,
`bridged`, `direction`, `sourceFile`, `lineStart`, and `lineEnd`. A chain can be
rendered without joining its edge IDs back to the edge collection. Text output
shows `hops=N` for each displayed chain.

`truncated` and `truncationReasons` describe deliberately bounded output.
`cycleDetected` means the complete deduplicated edge set contains a directed
cycle; it is a diagnostic and is not itself a truncation. There is no fixed
chain-count projection limit. `truncationReasons` can include `DEPTH_LIMIT`,
`EDGE_LIMIT`, `SEED_LIMIT`, and `TIME_LIMIT`. Edge and depth limits remain
explicit request options so callers can bound very large diagnostic queries
without silently shortening the default field-lineage result. `-1` is the only
automatic budget value; `0` and other negative values are rejected.

A dotted target is accepted only when an analysed scope contains an
evidence-backed field with exactly that root and access path; otherwise `trace`
returns `NO_MATCH` with `FIELD_NOT_FOUND` instead of inventing a path. Text
output identifies methods by complete signatures and values by expressions;
stable IDs are present only in JSON for machine joins.

| Option | Default | Effect |
|---|---:|---|
| `--method SIGNATURE` | Required | Method signature or stable method ID. |
| `--target EXPRESSION` | Required | Target from `targets`, such as `result.status`. |
| `--direction in / out / both` | `both` | Trace upstream, downstream, or both directions. |
| `--call-scope effective / full` | `effective` | Follow the selected method and callees, or also include callers. |
| `--include-dependencies` | `false` | Include control, call-input, and mutation-effect dependency edges. |
| `--project PATH` | Current detected root | Project to query. |
| `--format text / json` | `text` | Result output format. |
| `--no-sync` | `false` | Do not refresh the index before tracing. |
| `--allow-stale` | `false` | Read the previous generation if a refresh fails. |
| `--max-depth N` | Auto | Maximum traversal depth; `-1` selects 30 for field targets and 100 for object targets. `0` is invalid. |
| `--max-edges N` | Auto | Maximum number of traversed edges; `-1` selects 2500 for field targets and 5000 for object targets. `0` is invalid. |
| `--query-timeout SECONDS` | `0` | Maximum query time; `0` disables the limit. |
| `--language-level N` | Auto-detected | Override language level if a refresh is needed. |
| `--include-tests` | `false` | Include test source roots in the analysis. |
| `--generated-sources PATH` | None | Explicit generated-source root used for refresh and freshness checks. |
| `--classpath PATH` | None | Explicit JAR or class directory used for refresh, class-file metadata, and freshness checks. |
| `--classpath-file PATH` | None | Classpath file used for refresh and freshness checks. |
| `--allow-resolution exact / candidate` | `exact` | Accept candidate edges as complete output. Unresolved evidence still fails. |
| `--fail-on-candidate` | `false` | Return a non-zero exit code whenever candidate evidence is present, even with `--allow-resolution candidate`. |
| `--max-weak-hops N` | `-1` | Return exit code `6` when the trace exposes more weak hops than `N`; `-1` disables the gate. |
| `--strict-contracts` | `false` | Fail on incomplete, expired, or unmatched `.field-trace-graph/contracts.yml` entries. |

### `source`

```bash
field-trace-graph source \
  --file src/main/java/Example.java \
  [--start 1] [--end 20] \
  [OPTIONS]
```

Reads a bounded source excerpt for evidence review. The file path is relative to
the project root. The default range is line 1 through line 20.

| Option | Default | Effect |
|---|---:|---|
| `--file PATH` | Required | Relative source file path. |
| `--start N` | `1` | First line to read. |
| `--end N` | `20` | Last line to read. |
| `--project PATH` | Current detected root | Project containing the file. |
| `--format text / json` | `text` | Result output format. |
| `--language-level N` | Auto-detected | Override language level for the source scope. |
| `--include-tests` | `false` | Allow a file under a test source root. |

### `doctor`

```bash
field-trace-graph doctor [OPTIONS]
```

Checks the local runtime environment. It reports the Java version, whether a
compiler is available, whether SQLite JDBC is available, OS/architecture, and
whether the project index file exists. It does not create or update an index.

| Option | Default | Effect |
|---|---:|---|
| `--project PATH` | Current detected root | Project whose index presence is checked. |
| `--format text / json` | `text` | Result output format. |

## Environment Variables

Runtime size and timeout budgets can be overridden without editing the
properties file:

| Variable | Purpose |
|---|---|
| `FIELD_TRACE_GRAPH_PARSER_VERSION` | Override the parser/schema compatibility version. |
| `FIELD_TRACE_GRAPH_SOURCE_MAX_FILES` | Maximum source files in one scan. |
| `FIELD_TRACE_GRAPH_SOURCE_MAX_FILE_BYTES` | Maximum bytes per source file. |
| `FIELD_TRACE_GRAPH_SOURCE_MAX_TOTAL_BYTES` | Maximum total source bytes per scan. |
| `FIELD_TRACE_GRAPH_SOURCE_MAX_DEPTH` | Maximum source tree depth. |
| `FIELD_TRACE_GRAPH_INDEX_LOCK_TIMEOUT_MILLIS` | Default index lock wait in milliseconds. |
| `FIELD_TRACE_GRAPH_INDEX_TIMEOUT_MILLIS` | Default index timeout in milliseconds. |
| `FIELD_TRACE_GRAPH_QUERY_TIMEOUT_MILLIS` | Default trace query timeout in milliseconds. |
| `FIELD_TRACE_GRAPH_QUERY_MAX_DEPTH` | Default maximum traversal depth. |
| `FIELD_TRACE_GRAPH_QUERY_MAX_EDGES` | Default maximum traversal edges. |
| `FIELD_TRACE_GRAPH_QUERY_MAX_CALLEE_DEPTH` | Maximum callee expansion depth. |

Build and release variables include `JAVA_HOME`, `FTG_JAVA_HOME`,
`FTG_RUNTIME_HOME`, `FTG_BUNDLE_RUNTIME`, `FTG_STRICT_RELEASE`, `VERSION`, `OS`,
and `CPU`. `FTG_SKIP_BUILD=1` reuses an existing JAR during a release build;
`FTG_SKIP_SECURITY_SCAN=1` skips the security scan only when the caller has a
separate approved scan step.

## Semantic analysis and isolation

Compile-time diagnostics run in a separate `SemanticWorkerMain` subprocess launched
by `SemanticWorkerLauncher`. The worker is bounded by a timeout, heap limit, and
response size limit, and its private temp directory is always removed. Annotation
processors, implicit source discovery, and target project code are never executed.

## Generic types and framework adapters

Declared generic types are preserved for member resolution. A field or method
that returns `T` on `Wrapper<Order>` resolves to `Order`, and collection,
optional, future, and map reads resolve their element/key/value types instead
of erasing to `Object`. This is used by constructor, stream, BeanCopy, and
receiver-type decisions when the source declaration is available.

Implicit lambda parameters are typed from the collection, stream, optional or
map they iterate (`users.stream().map(user -> user.getName())` binds
`user` to the `List<User>` element). This keeps method dispatch inside lambda
bodies exact instead of degrading to an unknown-receiver boundary.

Bean serialization is a framework adapter: Fastjson `JSON.toJSONString`,
Jackson `writeValueAsString`, and Gson `toJson` expand declared fields into
field-to-result edges. Annotation-based exclusions such as `@JSONField(...,
serialize = false)` and `@JsonIgnore` are honoured. These edges are published
as `resolution=CANDIDATE` with `provenance=FRAMEWORK_ADAPTER`; import a runtime
trace to confirm the fields that were actually serialized.

MyBatis mapper XML is discovered by namespace rather than a hard-coded
`/mapper/` directory. Bind variables retain XML line/column metadata;
`<sql>` fragments reached through `<include>` are expanded for parameter
discovery, and `<foreach>` item aliases are mapped back to their collection
parameter. SQL bind edges reuse the existing Java parameter/field value node
when one exists, so the SQL boundary stays connected to the Java lineage chain.
Legacy iBatis 2 `<sqlMap>` files are also recognized when their namespace maps
to a type annotated with `@Dao(value = "...")`; `parameterClass` and
`resultClass` are accepted as aliases for `parameterType` and `resultType`, and
`#param#` / `$param$` bind syntax is normalized to the same SQL boundary model.

## Project detection and language level

The project root is the nearest Git worktree, `pom.xml`, or `build.gradle` from the
current directory, unless `--project` is given. Each module's Java language level is
read statically from `pom.xml` (`release`, `maven.compiler.release`,
`maven.compiler.source`, `source`) or `build.gradle` (`options.release`,
`sourceCompatibility`). A module inherits compiler configuration from parent
directories up to the project root. Modules with no static level fall back to Java
17 and are marked as assumed.

## Field-lineage semantic contract

- `both` defaults to `effective` call scope: the selected method and its callees.
  Caller expansion requires `--call-scope full`.
- Field-value traversal uses assignments, field writes, return values, container
  copies, constructor/Builder/BeanCopy flows, and explicit call argument/return
  binding. `ARGUMENT_TO` is a call-boundary transport edge, not an
  `ASSIGNED_FROM` value-source claim.
- `CONDITION_USES` and `CALL_INPUT_USES` are dependency evidence, not field-value
  edges. They are excluded by default and appear only with
  `--include-dependencies`.
- Logging, monitoring, and telemetry reads are not field lineage.
- Unknown external calls stop at `EXTERNAL_BOUNDARY`; they are never rewritten as
  confirmed field assignment edges.
- User-facing targets keep business fields primary. `RETURN` is `RETURN`, a
  constructor result is `CONSTRUCTOR_RESULT`, and an ordinary call result is
  `CALL_RESULT`. Getter/setter call syntax is normalized to its business field.
- `this.field`, a concrete variable field, and a constructor result field keep
  their owning scope. They share the same canonical field suffix, but fields from
  different receiver objects are not merged merely because their names match.
- Trace quality is deterministic and is calculated from field-value edges only.
  Candidate edges are reported separately from unresolved edges and make the
  result `PARTIAL` even when all candidate evidence is present. Unresolved value
  edges below 10% are `OK`; 10% to below 30%, or an actual truncation with
  unresolved value edges, is `PARTIAL`; 30% or more is `INSUFFICIENT`. A trace
  with no field-value evidence at all is `UNKNOWN`. A partial semantic index, an
  unresolved call-structure edge, or an unresolved diagnostic edge does not by
  itself downgrade complete field lineage. Those gaps remain visible in
  `structureStatus` and `diagnosticStatus`. Resolved edges remain available in
  every case.

## External classpath

Place an optional `.semantic-classpath.xml` at the project root. The canonical
format is shared with the PCA indexer:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<semanticClasspath version="1">
  <module path="auction-marketing-tool-common" sourceSet="main">
    <jar path="semantic-libs/commons-lang3-3.5.jar"
         sha256="64-lowercase-hex-characters"/>
  </module>
</semanticClasspath>
```

Paths are project-relative and each JAR hash is mandatory. The older
`<classpath><entry .../></classpath>` format remains readable for local
compatibility, but new files should use the canonical schema. The tool validates
each local JAR but never runs Maven, Gradle, annotation processors, or project
code, and never downloads dependencies. A missing manifest is allowed but
produces a `PARTIAL` graph when javac cannot resolve external types; an invalid
manifest is rejected instead of being silently ignored.

For a one-off build, pass the same entries explicitly:

```bash
field-trace-graph index \
  --classpath target/classes \
  --classpath ~/.m2/repository/com/example/api/1.0/api-1.0.jar
```

Directories and JARs are accepted. The tool reads class-file names, method
descriptors, and class-level annotations to distinguish `RPC_SERIALIZATION`,
`EXTERNAL_LIBRARY`, and `CLASSPATH_MISSING` without loading the class or running
its static initializer. `--classpath` is also part of the freshness fingerprint:
adding or removing an entry makes an existing index `STALE` until it is
rebuilt.

## Index location

The SQLite index is written to `.field-trace-graph/trace.db` inside the project root.
The tool does not modify source files.

## Output

Pass `--format json` for a single JSON object on stdout. Logs and progress go to
stderr. Exit codes: `0` success, `2` usage/not-found, `3` stale or missing index,
`4` internal/parse/storage error, `5` resource budget.

## Troubleshooting

**First run looks frozen** — it is downloading the platform archive (~200 MB).
This happens on the first invocation of any command, `--version` included.
The launcher writes `downloading <archive> (<size>)...` and
`downloaded <archive> (<size>) in Ns` to stderr; later runs start in under a
second.

**`init` hangs** — you are probably indexing a directory that is not a project
root (running it in `~` walks your entire home directory). Run it inside the
project, or pass `--project /path/to/project`.

**`ftg` and `field-trace-graph` behave differently** — they cannot: both are
symlinks to the same launcher. Run `type -a ftg`; an older shell alias will
shadow the installed command.

**Results look stale after editing code** — `init` and `index` refresh the
graph. Use `--no-sync` only when you deliberately want to read the existing
index.

**Getter/setter flows stop early on Lombok classes** — generated members are not
in the source. Produce delombok output and pass `--generated-sources <dir>` so
those accesses resolve.

**A trace ends at `CLASSPATH_MISSING`** — the receiver type is named in source
but no classpath entry confirms its members. Pass `--classpath` (or a
`--classpath-file`) pointing at the module's output directory or dependency JARs.

**`database is locked` or a corrupted cache** — remove
`~/.field-trace-graph/dist/` (launcher) and `.field-trace-graph/` (index); the
next run rebuilds both.

**Index is unexpectedly large** — check that `target/`, `build/`, and generated
directories are covered by `.gitignore`, and that generated source roots are
passed explicitly with `--generated-sources` instead of being indexed as regular
source.

## Development

### Requirements

- Published npm package: Node.js 18 or newer. No system JDK is required.
- Build from source: JDK 17 or 21 with `javac`, Maven, and Node.js 18 or newer.
- Release build: a redistributable JDK 17 or 21 selected with `FTG_RUNTIME_HOME`.

### Build

```bash
export JAVA_HOME=/path/to/jdk-17-or-21
mvn clean package
```

The shaded executable jar is `lineage-cli/target/field-trace-graph.jar`.

For a local JAR build, run it from any project root or pass `--project`:

```bash
cd /path/to/java-project
java -jar /path/to/field-trace-graph.jar init
java -jar /path/to/field-trace-graph.jar methods --name OrderService
java -jar /path/to/field-trace-graph.jar trace \
  --method 'com.example.OrderService#create(String,String)' \
  --target result.status
```

For shorter commands, create an alias in the current shell:

```bash
alias ftg='java -jar /path/to/field-trace-graph.jar'
ftg init
```

### Modules

- `lineage-core`: domain model, query rules, ports, and JDK-only shared utilities.
- `lineage-java`: Java AST extraction, type/call resolution, and semantic analysis.
- `lineage-storage-sqlite`: SQLite schema, SQL, row mapping, and transactions.
- `lineage-cli`: command controllers, application services, views, and assembly.
- `distribution/npm`: thin Node launcher for a global install.

### Development Commands

These commands operate on this repository, not on the Java project being
analyzed.

```bash
# Compile all modules and build the executable JAR.
mvn clean package
mvn -q clean package
```

This repository does not ship unit tests or a regression harness, so
`mvn test` has nothing to run. Build with `mvn clean package`.

If the local Maven cache was populated by a different Maven installation, or
the machine is offline, Maven may warn that an artifact was "cached from a
remote repository ID that is unavailable in current build context". The
artifacts are already present, so build offline or disable local-repository
tracking:

```bash
mvn -o clean package     # air-gapped build against a complete local cache
mvn -llr clean package   # legacy local repository, ignores cached repo IDs
```

The shaded executable is `lineage-cli/target/field-trace-graph.jar`. The
individual module JARs are build artifacts and are not the normal command-line
entry point.

### Diagnostic Commands

The local graph is SQLite and is safe to inspect directly. These commands are
read-only:

```bash
# List tables.
sqlite3 .field-trace-graph/trace.db ".tables"

# Show one published index state and table counts.
sqlite3 .field-trace-graph/trace.db <<'SQL'
SELECT 'files', COUNT(*) FROM source_file
UNION ALL SELECT 'symbols', COUNT(*) FROM source_code_symbol
UNION ALL SELECT 'nodes', COUNT(*) FROM value_node
UNION ALL SELECT 'edges', COUNT(*) FROM value_data_edge
UNION ALL SELECT 'call_edges', COUNT(*) FROM parsed_invoke_edge
UNION ALL SELECT 'method_summaries', COUNT(*) FROM method_summary
UNION ALL SELECT 'pending_refs', COUNT(*) FROM pending_parse_invoke_ref;

SELECT active_generation, graph_status, published_at,
       last_attempt_status, last_error_code
FROM full_index_state;
SQL
```

Useful checks:

- `files` should match the expected main Java source count when tests are
  excluded.
- `status` is the easiest way to check whether the latest generation is
  `READY`, `PARTIAL`, or stale.
- `doctor` distinguishes a missing runtime/compiler from a missing index.

#### Index counters and symbol dimensions

`ftg status` reports the counters below. They are the authoritative check that a
published generation covers the source tree you expect.

`parsed_invoke_edge` stores only calls that reached a callee (`EXACT_STATIC`,
`COMPILE_TIME_TARGET`, `VIRTUAL_UNIQUE`, `CANDIDATE`). Unresolved calls are
recorded once, in `pending_parse_invoke_ref`, together with the call-site payload
(dispatch kind, receiver, arguments, result value) needed to keep
receiver-to-result bridges during traversal. The two tables are therefore
disjoint and their counts can be compared with tools that separate pending
references. `ftg status` prints both numbers as `calls=` and `pending=`.

Symbol comparison uses separate dimensions instead of one `kind` column:

```text
declaration kind: CLASS / INTERFACE / ENUM / RECORD / ANNOTATION /
                  METHOD / CONSTRUCTOR / INITIALIZER
symbol role:      BUSINESS / ACCESSOR_GET / ACCESSOR_SET / INITIALIZER
```

Use these FTG counters for comparison:

```sql
SELECT
    COUNT(*) AS total_symbols,
    SUM(kind IN ('CLASS','INTERFACE','ENUM','RECORD','ANNOTATION')) AS type_count,
    SUM(kind = 'METHOD') AS method_count,
    SUM(kind = 'METHOD' AND symbol_role = 'BUSINESS') AS business_method_count,
    SUM(symbol_role IN ('ACCESSOR_GET','ACCESSOR_SET')) AS accessor_count,
    SUM(kind = 'CONSTRUCTOR') AS constructor_count,
    SUM(symbol_role = 'INITIALIZER') AS initializer_count
FROM source_code_symbol;
```

For a stable row-level key, use the declaration tuple rather than the numeric
symbol IDs:

```text
relative file path
+ declaring type fully-qualified name
+ method or constructor name
+ normalized parameter types
+ constructor flag
```

The branch and commit must match before interpreting a remaining difference as
a regression.

## Release

### Build A Release

Build a local archive with a bundled runtime:

```bash
export FTG_RUNTIME_HOME=/path/to/redistributable-jdk-17-or-21
FTG_STRICT_RELEASE=1 VERSION=0.1.0 OS=darwin CPU=arm64 distribution/build-release.sh
```

The archive contains `bin/`, `lib/`, `runtime/`, `licenses/`, the CycloneDX SBOM,
and `.version`. Its SHA-256 is written to `distribution/release-manifest.json`.
Set `FTG_BUNDLE_RUNTIME=0` only for local debugging; strict release builds require
the bundled runtime.

Run the fail-closed release gate before publishing:

```bash
FTG_VERIFY_PLATFORM_ONLY=darwin-arm64 distribution/verify-release.sh
```

`verify-release.sh` checks the license, npm license metadata, HTTPS download
base, archive hashes, archive layout, bundled runtime, runtime/third-party
licenses, SBOM, secret scan, and generated source/npm/archive SBOMs. See
`To-do/RELEASE-CHECKLIST.md` for the external ownership, hosting, and signing inputs
that cannot be inferred from source code.

### Release Commands

The release scripts are for maintainers and require the ownership, license,
hosting, and signing inputs listed in `To-do/RELEASE-CHECKLIST.md`.

```bash
# Build one platform archive with a bundled runtime.
export FTG_RUNTIME_HOME=/path/to/redistributable-jdk-17-or-21
FTG_STRICT_RELEASE=1 VERSION=0.1.0 OS=darwin CPU=arm64 \
  distribution/build-release.sh

# Run the fail-closed archive and manifest verification gate.
FTG_VERIFY_PLATFORM_ONLY=darwin-arm64 distribution/verify-release.sh

# Smoke-test the unpacked archive with its bundled runtime.
distribution/smoke-test-release.sh \
  distribution/build/field-trace-graph-0.1.0-darwin-arm64.tar.gz

# Build the npm tarball, install it into a temp prefix, and exercise the launcher.
distribution/smoke-test-npm.sh \
  distribution/build/field-trace-graph-0.1.0-darwin-arm64.tar.gz

# Run the source, npm tarball, and archive secret/SBOM scan.
distribution/security-scan.sh

# Build and smoke-test the npm launcher locally.
cd distribution/npm
npm pack
npm install -g ./field-trace-graph-0.1.0.tgz
field-trace-graph --version
field-trace-graph doctor
```

Internal release helpers:

```bash
# Copy the root release manifest into the npm package when packing.
node distribution/npm/scripts/sync-manifest.js

# Update one platform entry after an archive is built.
node distribution/update-release-manifest.js \
  darwin arm64 field-trace-graph-0.1.0-darwin-arm64.tar.gz "$SHA256"
```

The build script calls the manifest updater automatically. `npm pack` runs
`sync-manifest.js` through the package `prepack` script. `verify-release.sh`
calls `security-scan.sh` unless `FTG_SKIP_SECURITY_SCAN=1` is explicitly set.

## License

Apache License 2.0 — see [LICENSE](LICENSE).

<div align="center">

**Local Java field lineage — no server, no upload, no model.**

[Report a bug](https://github.com/rycan-top/field-trace-graph/issues) ·
[Request a feature](https://github.com/rycan-top/field-trace-graph/issues)

</div>

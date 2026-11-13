# 待办事项（To-do）

本目录集中存放项目尚未完成的工作项与其相关文件。原位于仓库根目录的
`RELEASE-CHECKLIST.md` 已移入本目录。

## 1. 发布前必须补齐

- [x] 项目许可证已选定为 **Apache-2.0**，根目录已新增 `LICENSE`，并补充 `NOTICE`。
- [x] `distribution/npm/package.json` 的 `license` 已改为 `Apache-2.0`，并补充 `repository` / `homepage` / `bugs`。
- [x] `baseUrl` 已设为 `https://github.com/rycan-top/field-trace-graph/releases/download/v0.1.0`（发布 tag 必须是 `v0.1.0`）。
- [ ] `darwin-x64`、`linux-x64`、`win32-x64` 三个平台的 `sha256` 仍为全 0 占位；目前只有 `darwin-arm64` 有真实哈希，需构建归档后回填。
- [ ] 配置 cosign 签名（`COSIGN_PRIVATE_KEY`、`COSIGN_PASSWORD`），并为归档与清单生成 `.sig` 文件。当前 workflow 不签名、门禁也不校验，发布产物实际为未签名状态。
- [x] 已新增 `.github/workflows/release.yml`：4 平台矩阵构建 → 回填哈希 → 门禁 → GitHub Release → npm publish。
- [ ] 需要在 GitHub 仓库配置 `NPM_TOKEN` secret，并推送 `v0.1.0` tag 触发发布。
- [ ] 发布前确认 GitHub 仓库地址是 `rycan-top/field-trace-graph`，且 `baseUrl` 里的 tag 与实际 tag 一致。

## 2. 构建与产物

- [ ] `distribution/build/` 已删除（原先包含 `field-trace-graph-0.1.0-darwin-arm64.tar.gz` 与 `stage-darwin-arm64/`），需要时用 `distribution/build-release.sh` 重新生成。
- [ ] `mvn package` 会在仓库根目录产出 `target/field-trace-graph-sbom.json`；该步骤需要联网执行 Maven（离线模式下 CycloneDX 插件会被跳过）。

## 3. 本次清理遗留的后续项

- [x] 已补 `.gitleaks.toml` 与 `.syft.yaml`（`security-scan.sh` 依赖这两个文件，此前缺失）；gitleaks 本地实测 0 findings，syft 配置尚未本地验证。
- [ ] 单元测试与回归脚本已整体删除：`*/src/test` 下的 22 个测试类，以及 `distribution/regression/` 下的 2 个 Python 回归脚本。
- [ ] 若要恢复质量保障，需要重新引入 `junit-jupiter` / `assertj-core` 依赖、surefire 配置与测试源码。
- [ ] `--include-tests` 系列命令行参数仍然保留，它们作用于“被分析的项目”，与本仓库自身的测试无关。
- [x] 厂商专有 RPC/监控标识已清理，并换成开源等价实现：
  - RPC 边界改为识别 Dubbo / gRPC / Thrift / CXF / RESTEasy / Feign / JAX-WS（`javax.jws`、`jakarta.jws`、`javax.xml.ws`），注解侧新增 `GrpcService`。
  - 监控埋点改为识别 Micrometer / OpenTelemetry / Prometheus / StatsD / Dropwizard / Actuator，以及 `observe`、`summary`、`startSpan`、`setAttribute` 等开源 API 动作。
  - `SemanticContextFingerprint.RULE_VERSION` 已从 0.5.2 提升到 0.5.3，旧索引会因规则变化自动失效并重建。

## 4. 相关文件

- [RELEASE-CHECKLIST.md](./RELEASE-CHECKLIST.md) — 发布门禁清单（原仓库根目录）。

## 5. 字段桥与完整性语义（已完成）

- [x] 虚调用 / RPC 服务接口调用按擦除参数类型匹配并桥接到**全部**源码实现；多实现产生 `CANDIDATE` 调用边，单实现保持 `VIRTUAL_UNIQUE`。
- [x] 接口来自依赖 JAR、实现类在源码内时，也能按 `--classpath` 建立实现桥。
- [x] 对象值 setter/getter 属性桥：`setXxx(request)` 后 `getXxx()` 返回的对象，其字段继续作为该对象的字段传播，不再生成 `holder.request.field`。
- [x] `NO_USE` 拆分：`USE_UNRESOLVED`（有消费方但未桥接）、`CONSUMED_BY_CALL`（值被调用消费）、`NO_CONSUMER`（源码内确实没有消费方）。
- [x] 完整性结论独立于边质量：相关弱跳、未桥接消费方、候选边与截断都会使 `completeness` 变为 `PARTIAL`；`quality` 仍只统计已发射的字段值边。
- [x] text 输出展示全部 chains，弱跳不再截断到前 10 条；其余折叠项会显式打印剩余数量。
- [x] `CONDITION_USES` 仍作为依赖证据保留，只在 `--include-dependencies` 时输出。

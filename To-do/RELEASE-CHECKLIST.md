# Release Checklist

The release gate is fail-closed. A release is publishable only when every item
below has evidence.

## Ownership And License

- [ ] Code ownership and redistribution permission confirmed by the owner.
- [ ] `LICENSE` selected by the owner and added at the repository root.
- [ ] `distribution/npm/package.json` uses the selected SPDX identifier instead of `UNLICENSED`.
- [ ] `THIRD-PARTY-NOTICES`, bundled runtime licenses, and `licenses/runtime-legal` present.

The gate intentionally does not choose a project license automatically. Changing
the npm license without the owner's authorization would make an invalid legal
claim.

## Build And SBOM

- [ ] `mvn clean package` passes and `target/field-trace-graph-sbom.json` exists.
- [ ] `scripts/bump-version.sh <version>` ran; the root POM revision, `release-manifest.json`, and npm `package.json` all match.
- [ ] `distribution/security-scan.sh` passes for source and generated artifacts.
- [ ] Archives built for every supported OS/CPU with a bundled JDK.

```bash
export FTG_RUNTIME_HOME=/path/to/redistributable-jdk-17-or-21
OS=darwin CPU=arm64 FTG_STRICT_RELEASE=1 distribution/build-release.sh
```

## Hosting And Manifest

- [ ] `distribution/release-manifest.json` uses an immutable HTTPS `baseUrl`.
- [ ] Every platform hash is populated and `distribution/verify-release.sh` passes.

```bash
FTG_VERIFY_PLATFORM_ONLY=darwin-arm64 distribution/verify-release.sh
```

The GitHub Actions `release` workflow builds all four target archives, merges
their hashes into the manifest, and runs the release gate. The download base URL
comes from `baseUrl` in `distribution/release-manifest.json`; there is no
`base_url` workflow input. Artifact signing is not automated yet (see Signing).

## npm And Smoke Tests

- [ ] npm tarball includes `release-manifest.json`, `bin`, and `lib`.
- [ ] Install test from the npm tarball passes on each supported platform.
- [ ] Final install smoke test runs `--version`, `doctor`, and `trace`.
- [ ] `distribution/smoke-test-release.sh ARCHIVE` passes against every native archive.
- [ ] `distribution/smoke-test-npm.sh ARCHIVE` passes against every native archive.

```bash
cd distribution/npm
npm pack
npm install -g ./field-trace-graph-*.tgz
field-trace-graph --version
field-trace-graph doctor
```

The archive smoke test creates a temporary Java project, runs `init`, and runs
`trace` using the bundled runtime. It must not fall back to a system JDK:

```bash
distribution/smoke-test-release.sh \
  distribution/build/field-trace-graph-*-darwin-arm64.tar.gz
```

The npm smoke test packs the thin launcher, installs it under a temporary
prefix, seeds the same local cache a first-use download would create, and
checks `--version` and `doctor` through the installed package:

```bash
distribution/smoke-test-npm.sh \
  distribution/build/field-trace-graph-*-darwin-arm64.tar.gz
```

## Signing

- [ ] `COSIGN_PRIVATE_KEY` and `COSIGN_PASSWORD` are configured as CI secrets.
- [ ] Release archives and the manifest have detached `.sig` files.
- [ ] Signatures are verified by a consumer before installation.

Not implemented yet: `release.yml` does not sign artifacts and
`verify-release.sh` does not check signatures, so a release can be published
unsigned. Until these items are implemented, treat published artifacts as
unsigned and record that exception in the release notes.
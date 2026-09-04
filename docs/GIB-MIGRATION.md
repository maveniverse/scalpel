# GIB Migration Guide

This document helps you migrate from [gitflow-incremental-builder (GIB)](https://github.com/gitflow-incremental-builder/gitflow-incremental-builder) to Scalpel. Both tools solve the same problem: build only what changed in a multi-module Maven project.

## Overview

GIB uses shallow heuristics. It treats a changed file as a module affected, then provides many configuration knobs to compensate. Scalpel invests in deep Maven model understanding so those workarounds become unnecessary.

The tradeoff: Scalpel has fewer knobs. If Scalpel's analysis is wrong, your escape hatches are `forceBuildModules`, `excludePaths`, and `enabled=false`. GIB gives you more ways to influence the result manually.

## Where Scalpel Does More

**Semantic POM analysis.** GIB treats a changed `pom.xml` like any other changed file. The module is rebuilt. Scalpel reads the old POM from the base commit and compares it field-by-field (dependencies, plugins, properties, repositories, resources). Cosmetic changes (reformatting, reordering, adding comments) do not trigger rebuilds.

**Property indirection.** When a parent POM changes `<spring.version>3.2</spring.version>` to `3.3`, Scalpel traces that property through managed dependency versions and detects which child modules actually use `${spring.version}`. GIB sees only that the parent POM changed.

**Transitive dependency detection.** Scalpel uses Maven's `ProjectDependenciesResolver` to check whether a changed managed dependency reaches a module transitively (compile, runtime, or test scope). A module that does not use `spring-core` directly but pulls it in through `spring-web` is correctly detected.

**Managed plugin tracking.** When a parent POM changes the managed version of a plugin (e.g. `maven-compiler-plugin`), Scalpel finds all modules that use that plugin and marks them affected.

**Profile-aware comparison.** POM changes inside inactive profiles are ignored. Changing a `<profile><id>release</id>` section during a normal `mvn verify` will not trigger rebuilds.

**Import-scope BOM detection.** When a reactor module used as a BOM (via `<scope>import</scope>` in `<dependencyManagement>`) changes its managed dependencies, Scalpel propagates the change to all importing modules.

**Resource filtering tracking.** When a property changes in a parent POM, Scalpel checks whether child modules with `<filtering>true</filtering>` reference that property in their resource files (e.g. `${app.version}` in `application.properties`).

**Source-set-aware propagation.** When only test sources (`src/test/`) change in a module, Scalpel does not rebuild downstream modules that depend on the production artifact. Only modules with a `<type>test-jar</type>` dependency are affected. In Apache Camel, this reduces a `camel-core` test change from 518 transitive dependents to 25 (measured 2026-08-27, see [POM Analysis](POM-ANALYSIS.md) for the reproduction).

**Skip-tests mode.** Scalpel offers `mode=skip-tests`, which builds all modules but only runs tests on affected ones. GIB has no equivalent. It either includes or excludes modules from the reactor.

**Structured JSON report.** Scalpel's `mode=report` produces a JSON file with per-module reasons (`SOURCE_CHANGE`, `POM_CHANGE`, `TRANSITIVE_DEPENDENCY`, `MANAGED_PLUGIN`, etc.), categories (`DIRECT`, `UPSTREAM`, `DOWNSTREAM`), and source sets (`main`, `test`). CI scripts can make fine-grained decisions based on this data.

**Plugin configuration semantic diff.** Plugin `<configuration>` blocks are compared as DOM trees, not strings. Whitespace changes, attribute reordering, and comment additions inside plugin config are ignored.

**Java 8 compatibility.** Scalpel requires Java 8+. GIB requires Java 11+.

## GIB Features Not in Scalpel

The following GIB features have no direct Scalpel equivalent. For each, we explain why Scalpel chose not to implement it.

**`buildUpstream` / `buildDownstream` three-way modes.** GIB offers `always`, `derived`, and `never` for both properties. The `derived` mode defers to Maven's `-am` / `-amd` flags. Scalpel uses simple booleans (`true`/`false`), which already cover the `always`/`never` cases. The `derived` mode adds complexity for a niche use case. Users who want CLI-driven control can set the booleans in `.mvn/maven.config` or override them on the command line per invocation.

**`buildUpstreamMode` (`changed` vs `impacted`).** GIB can compute upstream modules based on either the directly changed modules (`changed`) or the full impacted set including downstream (`impacted`). Scalpel always computes upstream of the full build set (directly affected plus downstream), which matches GIB's `impacted` mode. GIB's `changed` mode is actually risky. If a downstream module needs a transitive upstream dependency that the directly-changed module does not use, the build breaks with a compilation error. Scalpel's approach is safer by design.

**`excludeDownstreamModulesPackagedAs`.** GIB can skip downstream modules by packaging type (e.g. `jar,pom` to skip library dependents and only rebuild `war`/`ear` deployables). This is a workaround for rapid local development. It goes against Scalpel's philosophy. If you change a library, its downstream dependents should be rebuilt to catch breakage. Users with this need can set `scalpel.alsoMakeDependents=false` for a quick local cycle, or use `scalpel.enabled=false` to bypass Scalpel entirely.

**`includePathsMatching`.** GIB supports an include-only filter on changed files (only files matching the regex count as changes). Scalpel has no file-level include filter. Use `excludePaths` to ignore everything you do not care about instead. `scalpel.includePaths` is not a substitute. It scopes which modules Scalpel treats as affected, leaving change detection on the full diff. More importantly, Scalpel's semantic POM analysis handles the main case where GIB users need path filtering. Cosmetic POM changes (reformatting, reordering) do not trigger rebuilds in Scalpel, so there is no need to filter `pom.xml` changes via path patterns.

**`disableBranchComparison`.** GIB can skip branch comparison entirely and detect changes based solely on uncommitted or untracked files. This is useful for IDE-like workflows. Scalpel is CI-first. When no base branch is detected and none is configured, it is a no-op (full build). For local development, set `scalpel.uncommitted=true -Dscalpel.untracked=true` alongside a base branch. If you truly want only uncommitted changes without any branch diff, you can set `scalpel.baseBranch=HEAD`. The branch diff will be empty and only uncommitted or untracked changes will be detected.

**`loadImpactedDependenciesFrom`.** GIB can read an external file listing dependency GAVs to trigger rebuilds, bypassing git-based detection entirely. This is a niche CI integration feature for cross-repo dependency chains. Scalpel's `mode=report` serves the reverse direction. It produces structured output that external tooling can consume. If there is demand for the input direction, it can be added later.

**`logImpactedFormat` (`path` vs `gav`).** GIB can log impacted modules in either path or GAV (`groupId:artifactId`) format. Scalpel's impacted log uses paths, which is what most CI scripts need (directory-based filtering, GitHub Actions matrix, etc.). The JSON report already contains full GAV information for scripts that need artifact coordinates.

**`logProjectsMode`.** GIB can filter console output to show only `changed`, `impacted`, `all`, or `none` projects in the build log. Scalpel logs affected modules at INFO level and detailed analysis at DEBUG level. Users who need quieter or more verbose output can use Maven's standard logging controls (`-q` for quiet, `-X` for debug).

**`failOnMissingGitDir`.** GIB has a separate control for behavior when no `.git` directory is found. In Scalpel, a missing `.git` directory is caught before the `failSafe` handler and always results in a full build — `failSafe` has no effect on this path. There is no separate property for this scenario because the behavior is unconditionally safe.

**Authentication support.** GIB supports HTTP credential queries via native Git (`git credential fill`) and SSH key authentication via JGit agent. Scalpel relies on the credentials already configured in the Git environment. This is intentional. Git authentication should be configured at the OS or CI level (SSH agent, credential helper, CI tokens), not duplicated inside a Maven extension. JGit inherits the system's SSH and credential configuration automatically.

## Property Mapping

| GIB Property | Scalpel Equivalent | Notes |
|---|---|---|
| `gib.enabled` | `scalpel.enabled` | Same semantics |
| `gib.referenceBranch` | `scalpel.baseBranch` | Same semantics |
| `gib.disableIfBranchMatches` | `scalpel.disableOnBranch` | Same (regex CSV) |
| `gib.disableIfReferenceBranchMatches` | `scalpel.disableOnBaseBranch` | Same (regex CSV) |
| `gib.fetchReferenceBranch` | `scalpel.fetchBaseBranch` | Same |
| `gib.disableSelectedProjectsHandling` | `scalpel.disableOnSelectedProjects` | Inverted default |
| `gib.logImpactedTo` | `scalpel.impactedLog` | Same format |
| `gib.buildUpstream` | `scalpel.alsoMake` | Boolean (no `derived` mode) |
| `gib.buildDownstream` | `scalpel.alsoMakeDependents` | Boolean (no `derived` mode) |
| `gib.skipTestsForUpstreamModules` | `scalpel.skipTestsForUpstream` | Same |
| `gib.argsForUpstreamModules` | `scalpel.upstreamArgs` | CSV `key=value` |
| `gib.argsForDownstreamModules` | `scalpel.downstreamArgs` | CSV `key=value` |
| `gib.excludePathsMatching` | `scalpel.excludePaths` | Glob patterns (GIB uses regex) |
| `gib.skipIfPathMatches` | `scalpel.disableTriggers` | Glob patterns (GIB uses regex) |
| `gib.uncommitted` | `scalpel.uncommitted` | Default differs (`false` in Scalpel) |
| `gib.untracked` | `scalpel.untracked` | Default differs (`false` in Scalpel) |
| `gib.forceBuildModules` | `scalpel.forceBuildModules` | Same (regex CSV) |
| `gib.buildAll` | `scalpel.enabled=false` | Use enabled flag directly |
| `gib.buildAllIfNoChanges` | `scalpel.buildAllIfNoChanges` | Same |
| `gib.failOnError` | `scalpel.failSafe` | Inverted semantics (`failSafe=true` is like `failOnError=false`) |
| `gib.buildUpstreamMode` | *(no equivalent)* | Scalpel always uses the full impacted set |
| `gib.excludeDownstreamModulesPackagedAs` | *(no equivalent)* | Use `scalpel.alsoMakeDependents=false` for a broad local bypass, or CI scripting |
| `gib.includePathsMatching` | *(no equivalent)* | File-level include filter. Use `excludePaths` inversely. Not `scalpel.includePaths`, which is module-scoped |
| `gib.disableBranchComparison` | *(no equivalent)* | Set `scalpel.baseBranch=HEAD` together with `uncommitted`/`untracked`; without a base branch Scalpel returns before reading them |
| `gib.loadImpactedDependenciesFrom` | *(no equivalent)* | |
| `gib.logImpactedFormat` | *(no equivalent)* | JSON report contains GAV information |
| `gib.logProjectsMode` | *(no equivalent)* | |
| `gib.failOnMissingGitDir` | *(no equivalent)* | A missing `.git` always falls back to a full build, regardless of `failSafe` |
| `gib.compareToMergeBase` | *(no equivalent)* | Scalpel always uses merge-base |
| `gib.help` | *(no equivalent)* | |

## Migration Example

### Before (GIB in `.mvn/maven.config`)

```text
-Dgib.referenceBranch=refs/remotes/origin/main
-Dgib.disableIfBranchMatches=main,release/.*
-Dgib.fetchReferenceBranch=true
-Dgib.excludePathsMatching=.*\.md|LICENSE
-Dgib.skipTestsForUpstreamModules=true
-Dgib.forceBuildModules=.*-it
-Dgib.uncommitted=false
-Dgib.untracked=false
```

### After (Scalpel in `.mvn/maven.config`)

```text
-Dscalpel.baseBranch=origin/main
-Dscalpel.disableOnBranch=main,release/.*
-Dscalpel.fetchBaseBranch=true
-Dscalpel.excludePaths=*.md,LICENSE
-Dscalpel.skipTestsForUpstream=true
-Dscalpel.forceBuildModules=.*-it
```

Key differences:

* `scalpel.baseBranch` uses `origin/main` (not `refs/remotes/origin/main`)
* `scalpel.excludePaths` uses glob patterns (not regex)
* `uncommitted` and `untracked` default to `false`, so they can be omitted
* `failSafe` defaults to `true` (fail-open: errors fall back to a full build); GIB's `failOnError` defaults to `true` (fail-closed: errors fail the build), so the out-of-the-box behavior differs

## See Also

* [Configuration Reference](CONFIGURATION.md)
* [Report Format](REPORT-FORMAT.md)
* [POM Analysis Details](POM-ANALYSIS.md)

# Maveniverse Scalpel

Requirements:
* Java: 8+
* Maven: 3.9.x+

Scalpel is a Maven core extension that detects which modules in a multi-module reactor are affected by
a git changeset. It can trim the reactor to only build affected modules, skip tests on unaffected modules,
or produce a JSON report of affected modules for consumption by CI scripts.

## How It Works

Scalpel hooks into Maven's lifecycle via `AbstractMavenLifecycleParticipant.afterProjectsRead()` and performs
the following steps:

1. **Check disable conditions** — skip if disabled by property, branch match, or `-pl` selection.
2. **Fetch base branch** (if configured) — in CI with shallow clones, fetch the base branch ref.
3. **Find the merge base** between the current HEAD and the configured base branch using JGit.
4. **Detect changed files** by diffing the merge base against HEAD, optionally including
   uncommitted and untracked files.
5. **Apply path filters** — check disable triggers, exclude paths, and full-build triggers.
6. **Map source changes to modules** — non-POM file changes are mapped to the owning module by directory
   prefix matching.
7. **Analyze POM changes directly** — for changed `pom.xml` files, the old POM is read from the base commit
   and compared field-by-field. Only modules whose POM actually changed (dependencies, plugins, properties,
   etc.) are marked as affected. Property indirection is resolved: if a parent POM changes a property used
   in a managed dependency version (`${spring.version}`), child modules using that dependency are detected.
8. **Check transitive impact** — modules not directly affected are checked for transitive exposure to changed
   managed dependencies (via dependency resolution) and changed managed plugins.
9. **Add force-build modules** — always include modules matching `forceBuildModules` patterns.
10. **Apply the selected mode** — trim the reactor (with upstream/downstream control), skip tests,
    or write a report.

## Usage

Add Scalpel to your project's `.mvn/extensions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
    <extension>
        <groupId>eu.maveniverse.maven.scalpel</groupId>
        <artifactId>extension</artifactId>
        <version>${version.scalpel}</version>
    </extension>
</extensions>
```

Then run Maven as usual. On a feature branch, Scalpel will automatically detect the base branch on
supported CI systems and trim the reactor:

```text
$ mvn verify
[INFO] Scalpel 0.1.0 activated (mode=trim)
[INFO] Scalpel: 3 changed files detected
[INFO] Scalpel: 2 modules directly affected: [com.example:module-a, com.example:module-b]
[INFO] Scalpel: Building 3 of 8 modules: [com.example:parent, com.example:module-a, com.example:module-b]
```

## Modes

Scalpel supports three modes, selected via `-Dscalpel.mode=<mode>`:

### `trim` (default)

Removes unaffected modules from the reactor. Only affected modules and their upstream/downstream
dependencies are built. This is the most aggressive mode — unaffected modules are completely skipped.

```bash
mvn verify -Dscalpel.baseBranch=origin/main
```

### `skip-tests`

Builds all modules but skips tests on modules that are not affected by changes. Modules affected by
transitive dependency or managed plugin changes also have their tests run. This is useful when you
want a full compile but only want to test what changed.

```bash
mvn verify -Dscalpel.mode=skip-tests -Dscalpel.baseBranch=origin/main
```

### `report`

Writes a JSON report of affected modules to a file without modifying the reactor. All modules are
built normally. This is useful when a CI script needs Scalpel's change detection results but wants
to control the build flow itself.

```bash
mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main
```

The report is written to `target/scalpel-report.json` by default (configurable via `scalpel.reportFile`).

### Impacted Module Log

For CI scripts that need a simple flat file (e.g. for GitHub Actions matrix filtering), Scalpel can
write a list of directly impacted module paths — one per line:

```bash
mvn validate -Dscalpel.mode=report -Dscalpel.impactedLog=target/scalpel-impacted.log
```

Example output:
```text
module-a
module-b/sub-module
```

This works in all modes (trim, skip-tests, report) and is written alongside the JSON report,
not as a replacement. It contains only directly affected modules (not upstream/downstream).

#### Report Format

```json
{
  "version": "1",
  "scalpelVersion": "0.1.0",
  "baseBranch": "origin/main",
  "fullBuildTriggered": false,
  "triggerFile": null,
  "changedFiles": ["module-a/src/main/java/Foo.java", "module-b/src/test/java/BarTest.java"],
  "changedProperties": [],
  "changedManagedDependencies": [],
  "changedManagedPlugins": [],
  "affectedModules": [
    {
      "groupId": "com.example",
      "artifactId": "module-a",
      "path": "module-a",
      "reasons": ["SOURCE_CHANGE"],
      "category": "DIRECT",
      "sourceSet": "main"
    },
    {
      "groupId": "com.example",
      "artifactId": "module-b",
      "path": "module-b",
      "reasons": ["TEST_CHANGE"],
      "category": "DIRECT",
      "sourceSet": "test"
    }
  ]
}
```

**Affected module reasons:**

| Reason | Description |
|--------|-------------|
| `SOURCE_CHANGE` | A non-POM, non-test file changed in this module's directory |
| `TEST_CHANGE` | Only test source files (`src/test/**`) changed; production artifact is unchanged |
| `POM_CHANGE` | This module's POM is affected by a POM change (property, dependency, or plugin) |
| `TRANSITIVE_DEPENDENCY` | A changed managed dependency reaches this module transitively (compile/runtime scope) |
| `TRANSITIVE_DEPENDENCY_TEST` | A changed managed dependency reaches this module transitively via test scope only |
| `MANAGED_PLUGIN` | This module uses a plugin whose managed version changed |
| `UPSTREAM_DEPENDENCY` | Included as an upstream dependency (via `alsoMake`) |
| `DOWNSTREAM_DEPENDENT` | Included as a downstream dependent (via `alsoMakeDependents`) |
| `DOWNSTREAM_TEST` | Included as a downstream dependent via test-scoped dependency only |
| `FORCE_BUILD` | This module was force-included via `forceBuildModules` |

**Affected module source sets** (present on directly affected modules with source changes):

| Source Set | Description |
|------------|-------------|
| `main` | Main source files (`src/main/**`) changed; all downstream dependents are affected |
| `test` | Only test source files (`src/test/**`) changed; only test-jar dependents are affected |

**Affected module categories:**

| Category | Description |
|----------|-------------|
| `DIRECT` | Directly affected by a change |
| `UPSTREAM` | Included as an upstream dependency (via `alsoMake`) |
| `DOWNSTREAM` | Included as a downstream dependent (via `alsoMakeDependents`) |

## Source-Set-Aware Downstream Propagation

Scalpel distinguishes between main source changes (`src/main/`) and test-only changes (`src/test/`)
when propagating changes to downstream modules:

- **Test-only changes** (`src/test/` only): the module itself is built and tested (DIRECT), but
  only downstream modules that declare a `<type>test-jar</type>` dependency on it are included.
  Regular dependents are NOT affected, since the main artifact is unchanged.

- **Main source changes** (`src/main/` with or without `src/test/`): all downstream dependents
  (regular and test-jar) are included, as the main artifact may have changed.

- **POM or resource changes**: treated like main source changes — all dependents are included.

This dramatically reduces unnecessary builds. For example, in Apache Camel, `camel-core` has ~500
regular dependents but only ~23 test-jar dependents. A change to a test base class in `camel-core`
triggers testing of only those 23 modules instead of all 500+.

Test-jar dependencies are declared in Maven as:

```xml
<dependency>
    <groupId>org.apache.camel</groupId>
    <artifactId>camel-core</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

In `report` mode, each directly affected module includes a `"sourceSet"` field (`"main"` or `"test"`)
indicating which source set triggered the change.

## Configuration

All properties can be set via `-D` on the command line or in `.mvn/maven.config`.

| Property | Default | Description |
|----------|---------|-------------|
| `scalpel.enabled` | `true` | Enable/disable Scalpel |
| `scalpel.baseBranch` | *(auto-detected)* | Base branch to compare against (e.g. `origin/main`) |
| `scalpel.head` | `HEAD` | The commit to compare (usually left as default) |
| `scalpel.mode` | `trim` | Operating mode: `trim`, `skip-tests`, or `report` |
| `scalpel.alsoMake` | `true` | Include upstream dependencies of affected modules (trim mode) |
| `scalpel.alsoMakeDependents` | `true` | Include downstream dependents of affected modules (trim mode) |
| `scalpel.fullBuildTriggers` | `.mvn/**` | Comma-separated glob patterns; if a changed file matches, a full build is triggered |
| `scalpel.excludePaths` | *(none)* | Comma-separated glob patterns; changed files matching these are ignored |
| `scalpel.disableTriggers` | *(none)* | Comma-separated glob patterns; if any changed file matches, Scalpel is disabled entirely |
| `scalpel.reportFile` | `target/scalpel-report.json` | Path for the JSON report (report mode), relative to reactor root |
| `scalpel.impactedLog` | *(none)* | Write impacted module paths to this file (one per line) |
| `scalpel.forceBuildModules` | *(none)* | Comma-separated regex patterns; always include modules whose artifactId matches |
| `scalpel.buildAllIfNoChanges` | `false` | Build everything when no changes are detected (useful for cron builds) |
| `scalpel.disableOnBranch` | *(none)* | Comma-separated regex patterns; disable if current branch matches |
| `scalpel.disableOnBaseBranch` | *(none)* | Comma-separated regex patterns; disable if base branch matches |
| `scalpel.disableOnSelectedProjects` | `false` | Disable Scalpel when `-pl` is used |
| `scalpel.skipTestsForUpstream` | `false` | Skip tests on modules included only as upstream dependencies |
| `scalpel.upstreamArgs` | *(none)* | Comma-separated `key=value` properties to set on upstream-only modules |
| `scalpel.downstreamArgs` | *(none)* | Comma-separated `key=value` properties to set on downstream-only modules |
| `scalpel.fetchBaseBranch` | `false` | Fetch base branch from remote before change detection |
| `scalpel.uncommitted` | `false` | Include uncommitted (staged + unstaged) changes |
| `scalpel.untracked` | `false` | Include untracked files in change detection |
| `scalpel.failSafe` | `true` | On error, fall back to a full build instead of failing |

## Local Developer Usage

By default, Scalpel only considers committed changes (diff between merge base and HEAD).
For local development, you can include uncommitted and/or untracked files:

```text
# In .mvn/maven.config for local dev:
-Dscalpel.uncommitted=true
-Dscalpel.untracked=true
```

This detects staged, unstaged, and new files without requiring a commit. CI environments
should leave these as `false` (the default).

## CI Auto-Detection

Scalpel automatically detects the base branch on common CI systems:

| CI System | Environment Variable |
|-----------|---------------------|
| GitHub Actions | `GITHUB_BASE_REF` |
| GitLab CI | `CI_MERGE_REQUEST_TARGET_BRANCH_NAME` |
| Jenkins | `CHANGE_TARGET` |

If no CI environment is detected and `scalpel.baseBranch` is not set, Scalpel is a **no-op** —
all modules are built normally. This makes it safe to keep Scalpel in `.mvn/extensions.xml` permanently
without affecting local developer builds.

### Fetching the Base Branch

In CI environments with shallow clones or forks, the base branch may not exist locally.
Scalpel can fetch it automatically before change detection:

```bash
-Dscalpel.fetchBaseBranch=true
```

This parses the base branch reference (e.g. `origin/main`) to extract the remote and branch
name, then fetches only that ref. If the ref already exists locally, no fetch is performed.

## Full Build Triggers

By default, changes to files under `.mvn/` (e.g. `extensions.xml`, `maven.config`) trigger a full
build. You can customize this with comma-separated glob patterns:

```bash
-Dscalpel.fullBuildTriggers=.mvn/**,Jenkinsfile,*.gradle
```

> **Note:** Since Scalpel itself lives in `.mvn/extensions.xml`, any change to that file (e.g. a
> Dependabot PR bumping Scalpel's version) will trigger a full build by default. If this is undesired,
> override the triggers to be more specific or set them to empty:
> ```bash
> -Dscalpel.fullBuildTriggers=
> ```

## Path Filtering

Scalpel provides three levels of path-based control over change detection. All use
comma-separated glob patterns:

- **`scalpel.excludePaths`** — Ignore matching files from change detection. Use this for files
  that shouldn't trigger rebuilds (documentation, editor config, etc.):
  ```bash
  -Dscalpel.excludePaths=*.md,LICENSE,.sdkmanrc,.editorconfig
  ```

- **`scalpel.fullBuildTriggers`** — Force a full build if any changed file matches (default:
  `.mvn/**`). See [Full Build Triggers](#full-build-triggers).

- **`scalpel.disableTriggers`** — Disable Scalpel entirely if any changed file matches. Use this
  when certain changes (e.g. CI configuration) should always result in a full, unmodified build:
  ```bash
  -Dscalpel.disableTriggers=.github/**
  ```

These filters are applied in order: disable triggers are checked first, then excluded paths are
removed, then full build triggers are checked on the remaining files.

## Force-Build Modules

Some modules should always be built regardless of change detection (e.g. integration test runners).
Use `forceBuildModules` with regex patterns matching artifactIds:

```bash
-Dscalpel.forceBuildModules=.*-it,.*-tests
```

For scheduled/cron builds where no changes may be detected, use `buildAllIfNoChanges` to fall back
to a full build instead of building nothing:

```bash
-Dscalpel.buildAllIfNoChanges=true
```

## Disabling Scalpel

To run a full build without Scalpel:

```bash
mvn verify -Dscalpel.enabled=false
```

### Branch-Based Disable

Scalpel can automatically disable itself based on the current or base branch name. This is useful
for CI systems where incremental builds should be skipped on main, release, or maintenance branches:

```bash
# Disable on main and release branches
-Dscalpel.disableOnBranch=main,release/.*

# Disable when the base branch is a maintenance branch
-Dscalpel.disableOnBaseBranch=\d+\.\d+
```

Branch patterns are Java regular expressions. For `disableOnBaseBranch`, the remote prefix
(e.g. `origin/`) is stripped before matching.

### Selected Projects (`-pl`) Handling

By default, when Maven is invoked with `-pl` (selected projects), Scalpel trims within
the `-pl` scope — this is usually the correct behavior. However, in CI downstream test
jobs where `-pl` selects a specific test module, you may want to disable Scalpel entirely:

```bash
# In CI downstream test jobs:
mvn verify -pl integration-tests/maven -Dscalpel.disableOnSelectedProjects=true
```

## Upstream and Downstream Module Handling

When Scalpel detects affected modules, it includes upstream dependencies (`alsoMake`) and
downstream dependents (`alsoMakeDependents`) in the build set. The following properties give
fine-grained control over how these categories are treated:

**Skip tests on upstream modules:**

```bash
mvn verify -Dscalpel.skipTestsForUpstream=true
```

This builds upstream dependencies but skips their tests, since they weren't directly changed.

**Inject properties per category:**

```bash
mvn verify -Dscalpel.upstreamArgs=skipITs=true -Dscalpel.downstreamArgs=skipITs=true
```

In `report` mode, each affected module in the JSON report includes a `"category"` field
(`DIRECT`, `UPSTREAM`, or `DOWNSTREAM`) so CI scripts can apply their own policies.

## POM Change Analysis

When a `pom.xml` file changes, Scalpel doesn't blindly mark the module as affected. Instead, it reads
the old POM from the base commit and compares it field-by-field with the current POM. The following
aspects are compared:

* Packaging
* Dependencies and dependency management
* Properties
* Build configuration (plugins, plugin management, plugin executions)
* Source directories (`sourceDirectory`, `testSourceDirectory`, `scriptSourceDirectory`)
* Resource and test resource configuration (directory, targetPath, includes, excludes, filtering)
* Repositories and plugin repositories (id, url, layout, snapshot/release policies)
* Active profile sections (properties, dependencies, plugins within active profiles)

This means cosmetic POM changes (reformatting, reordering, adding comments) won't trigger unnecessary
rebuilds. Plugin configurations are compared semantically (Xpp3Dom structure), so whitespace-only
changes in plugin XML configuration are ignored.

### Profile Awareness

Scalpel only considers changes inside profiles that are currently active. Changes to inactive profiles
are ignored, preventing unnecessary rebuilds when modifying profiles that don't apply to the current
build.

### Import-Scope BOM Detection

Scalpel detects managed dependency changes in reactor modules that are imported as BOMs via
`<scope>import</scope>` in another module's `<dependencyManagement>`. When a BOM module's managed
dependencies change, Scalpel propagates those changes to all importing modules — just as it does
for parent-inherited managed dependencies.

For example, given this reactor layout:

```text
parent/
├── bom/          (defines managed deps: commons-lang ${lib.version})
├── module-a/     (imports bom via <scope>import</scope>, uses commons-lang)
└── module-b/     (no BOM import)
```

If `bom/pom.xml` changes `lib.version` from `3.12` to `3.14`, Scalpel detects that:
- `module-a` is directly affected (it imports the BOM and uses `commons-lang`)
- `module-b` is not affected (it doesn't import the BOM or use the dependency)

This works with all POM analysis features: property indirection, managed plugin changes,
and transitive dependency checking.

### Property Indirection

Scalpel resolves property indirection in managed dependencies and plugins. For example, if a parent POM
changes `<kafka.version>3.6.0</kafka.version>` to `<kafka.version>3.7.0</kafka.version>`, and a managed
dependency uses `<version>${kafka.version}</version>`, Scalpel detects that the managed dependency's
effective version changed and marks child modules that use it as affected.

### Resource Filtering

When a property changes in a parent POM, Scalpel checks child modules that have resource filtering
enabled (`<filtering>true</filtering>`). If any filtered resource file contains a `${property}`
reference to the changed property, the module is marked as affected. This ensures that changes to
properties used in filtered resources (e.g. configuration files with `${app.version}`) trigger a
rebuild of the affected module.

## Git Worktree Support

Scalpel works correctly in git worktrees, where `.git` is a file pointing to the main repository
rather than a directory.

## Scalpel vs GIB (gitflow-incremental-builder)

[GIB](https://github.com/gitflow-incremental-builder/gitflow-incremental-builder) is the
established tool in this space. Both tools solve the same problem — build only what changed
in a multi-module Maven project. This section honestly compares the two so you can decide
which fits your project.

### Where Scalpel Does More

**Semantic POM analysis.** GIB treats a changed `pom.xml` like any other changed file — the
module is rebuilt. Scalpel reads the old POM from the base commit and compares it field-by-field
(dependencies, plugins, properties, repositories, resources). Cosmetic changes (reformatting,
reordering elements, adding comments) don't trigger rebuilds.

**Property indirection.** When a parent POM changes `<spring.version>3.2</spring.version>` to
`3.3`, Scalpel traces that property through managed dependency versions and detects which child
modules actually use `${spring.version}`. GIB sees only that the parent POM changed.

**Transitive dependency detection.** Scalpel uses Maven's `ProjectDependenciesResolver` to check
whether a changed managed dependency reaches a module transitively (compile, runtime, or test
scope). A module that doesn't use `spring-core` directly but pulls it in through `spring-web` is
correctly detected.

**Managed plugin tracking.** When a parent POM changes the managed version of a plugin (e.g.
`maven-compiler-plugin`), Scalpel finds all modules that use that plugin and marks them affected.

**Profile-aware comparison.** POM changes inside inactive profiles are ignored. Changing a
`<profile><id>release</id>` section during a normal `mvn verify` won't trigger rebuilds.

**Import-scope BOM detection.** When a reactor module used as a BOM (via `<scope>import</scope>`
in `<dependencyManagement>`) changes its managed dependencies, Scalpel propagates the change to
all importing modules.

**Resource filtering tracking.** When a property changes in a parent POM, Scalpel checks whether
child modules with `<filtering>true</filtering>` reference that property in their resource files
(e.g. `${app.version}` in `application.properties`).

**Source-set-aware propagation.** When only test sources (`src/test/**`) change in a module,
Scalpel does not rebuild downstream modules that depend on the production artifact. Only modules
with a `<type>test-jar</type>` dependency are affected. In Apache Camel, this reduces a
`camel-core` test change from ~500 downstream modules to ~23.

**Skip-tests mode.** Scalpel offers `mode=skip-tests`, which builds all modules but only runs
tests on affected ones. GIB has no equivalent — it either includes or excludes modules from the
reactor.

**Structured JSON report.** Scalpel's `mode=report` produces a JSON file with per-module reasons
(`SOURCE_CHANGE`, `POM_CHANGE`, `TRANSITIVE_DEPENDENCY`, `MANAGED_PLUGIN`, etc.), categories
(`DIRECT`, `UPSTREAM`, `DOWNSTREAM`), and source sets (`main`, `test`). CI scripts can make
fine-grained decisions based on this data.

**Plugin configuration semantic diff.** Plugin `<configuration>` blocks are compared as DOM trees,
not strings. Whitespace changes, attribute reordering, and comment additions inside plugin config
are ignored.

**Java 8 compatibility.** Scalpel requires Java 8+. GIB requires Java 11+.

### GIB Features Not in Scalpel

The following GIB features have no direct Scalpel equivalent. For each, we explain why Scalpel
chose not to implement it — sometimes because it's already covered differently, sometimes because
Scalpel's deeper analysis makes the feature unnecessary.

**`buildUpstream` / `buildDownstream` three-way modes.** GIB offers `always`, `derived`, and
`never` for both properties. The `derived` mode defers to Maven's `-am` / `-amd` flags —
upstream/downstream modules are only included if the user passes those CLI flags explicitly.
Scalpel uses simple booleans (`true`/`false`), which already cover the `always`/`never` cases.
The `derived` mode adds complexity for a niche use case; users who want CLI-driven control
can set the booleans in `.mvn/maven.config` or override them on the command line per invocation.

**`buildUpstreamMode` (`changed` vs `impacted`).** GIB can compute upstream modules based on
either the directly changed modules (`changed`) or the full impacted set including downstream
(`impacted`). Scalpel always computes upstream of the full build set (directly affected +
downstream), which matches GIB's `impacted` mode. GIB's `changed` mode is actually risky — if a
downstream module needs a transitive upstream dependency that the directly-changed module doesn't
use, the build breaks with a compilation error. Scalpel's approach is safer by design.

**`excludeDownstreamModulesPackagedAs`.** GIB can skip downstream modules by packaging type
(e.g. `jar,pom` to skip library dependents and only rebuild `war`/`ear` deployables). This is
a workaround for rapid local development — "I changed a library, just rebuild the deployables."
It's a manual override, not smarter detection, and goes against Scalpel's philosophy: if you
change a library, its downstream dependents *should* be rebuilt to catch breakage. Users with
this need can set `scalpel.alsoMakeDependents=false` for a quick local cycle, or use
`scalpel.enabled=false` to bypass Scalpel entirely.

**`includePathsMatching`.** GIB supports an include-only path filter (only files matching the
regex count as changes). Scalpel only has `excludePaths`. In practice, the two are interchangeable
— if you want only `.java` files to trigger rebuilds, exclude everything else. More importantly,
Scalpel's semantic POM analysis handles the main case where GIB users need path filtering:
cosmetic POM changes (reformatting, reordering) don't trigger rebuilds in Scalpel, so there's
no need to filter `pom.xml` changes via path patterns.

**`disableBranchComparison`.** GIB can skip branch comparison entirely and detect changes based
solely on uncommitted/untracked files — useful for IDE-like workflows where you want "what have
I changed since my last commit." Scalpel is CI-first: when no base branch is detected and none
is configured, it's a no-op (full build). For local development, set
`scalpel.uncommitted=true -Dscalpel.untracked=true` alongside a base branch. If you truly want
only uncommitted changes without any branch diff, you can set `scalpel.baseBranch=HEAD` — the
branch diff will be empty and only uncommitted/untracked changes will be detected.

**`loadImpactedDependenciesFrom`.** GIB can read an external file listing dependency GAVs to
trigger rebuilds, bypassing git-based detection entirely. This is a niche CI integration feature
for cross-repo dependency chains where an upstream pipeline knows what changed. Scalpel's
`mode=report` serves the reverse direction — it produces structured output that external tooling
can consume. If there's demand for the input direction, it can be added later.

**`logImpactedFormat` (`path` vs `gav`).** GIB can log impacted modules in either path or GAV
(`groupId:artifactId`) format. Scalpel's impacted log uses paths, which is what most CI scripts
need (directory-based filtering, GitHub Actions matrix, etc.). The JSON report already contains
full GAV information for scripts that need artifact coordinates.

**`logProjectsMode`.** GIB can filter console output to show only `changed`, `impacted`, `all`,
or `none` projects in the build log. Scalpel logs affected modules at INFO level and detailed
analysis at DEBUG level. Users who need quieter or more verbose output can use Maven's standard
logging controls (`-q` for quiet, `-X` for debug).

**`failOnMissingGitDir`.** GIB has a separate control for behavior when no `.git` directory is
found. Scalpel's `failSafe` property covers this — when `.git` is missing, Scalpel falls back
to a full build (or throws if `failSafe=false`). A separate property for this specific scenario
would add granularity without practical benefit.

**Authentication support.** GIB supports HTTP credential queries via native Git
(`git credential fill`) and SSH key authentication via JGit agent. Scalpel relies on the
credentials already configured in the Git environment. This is intentional: git authentication
should be configured at the OS/CI level (SSH agent, credential helper, CI tokens), not duplicated
inside a Maven extension. JGit inherits the system's SSH and credential configuration
automatically.

### Design Philosophy

The two tools have fundamentally different approaches:

**GIB** uses shallow heuristics — "file changed → module affected" — and provides many
configuration knobs to compensate: force-build lists, path exclusions, packaging filters,
per-category argument injection. This is flexible but requires tuning per project and can break
silently when the project structure changes.

**Scalpel** invests in deep Maven model understanding so that those workarounds become unnecessary.
Property indirection, transitive dependency resolution, semantic POM comparison, and source-set
awareness mean fewer false positives (unnecessary rebuilds) and fewer false negatives (missed
changes) without configuration.

The tradeoff: Scalpel has fewer knobs, which means less flexibility when you need to override its
decisions. If Scalpel's analysis is wrong, your escape hatches are `forceBuildModules`,
`excludePaths`, and `enabled=false`. GIB gives you more ways to influence the result manually.

### Property Mapping

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
| `gib.failOnError` | `scalpel.failSafe` | Inverted semantics (`failSafe=true` ≈ `failOnError=false`) |
| `gib.buildUpstreamMode` | *(no equivalent)* | Scalpel always uses the full impacted set |
| `gib.excludeDownstreamModulesPackagedAs` | *(no equivalent)* | Use `forceBuildModules` or CI scripting |
| `gib.includePathsMatching` | *(no equivalent)* | Use `excludePaths` to ignore unwanted files |
| `gib.disableBranchComparison` | *(no equivalent)* | Use `uncommitted`/`untracked` without setting `baseBranch` |
| `gib.loadImpactedDependenciesFrom` | *(no equivalent)* | |
| `gib.logImpactedFormat` | *(no equivalent)* | JSON report contains GAV information |
| `gib.logProjectsMode` | *(no equivalent)* | |
| `gib.failOnMissingGitDir` | `scalpel.failSafe` | Covered by general fail-safe behavior |
| `gib.compareToMergeBase` | *(no equivalent)* | Scalpel always uses merge-base |
| `gib.help` | *(no equivalent)* | |

### Migration Example

**Before (GIB in `.mvn/maven.config`):**

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

**After (Scalpel in `.mvn/maven.config`):**

```text
-Dscalpel.baseBranch=origin/main
-Dscalpel.disableOnBranch=main,release/.*
-Dscalpel.fetchBaseBranch=true
-Dscalpel.excludePaths=*.md,LICENSE
-Dscalpel.skipTestsForUpstream=true
-Dscalpel.forceBuildModules=.*-it
```

Key differences:
- `scalpel.baseBranch` uses `origin/main` (not `refs/remotes/origin/main`)
- `scalpel.excludePaths` uses glob patterns (not regex)
- `uncommitted` and `untracked` default to `false`, so they can be omitted
- `failSafe` defaults to `true` (GIB's `failOnError` defaults to `true`)

## Snapshot Repository

Snapshot builds are published on every push to `main`:

```xml
<repositories>
    <repository>
        <id>sonatype-snapshots</id>
        <url>https://central.sonatype.com/repository/maven-snapshots/</url>
        <releases>
            <enabled>false</enabled>
        </releases>
        <snapshots>
            <enabled>true</enabled>
        </snapshots>
    </repository>
</repositories>
```

Look into ITs for more usage examples.

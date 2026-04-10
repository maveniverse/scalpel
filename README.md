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
  "changedFiles": ["parent/pom.xml"],
  "changedProperties": ["kafka-version"],
  "changedManagedDependencies": ["org.apache.kafka:kafka-clients"],
  "changedManagedPlugins": [],
  "affectedModules": [
    {
      "groupId": "com.example",
      "artifactId": "module-a",
      "path": "module-a",
      "reasons": ["POM_CHANGE"],
      "category": "DIRECT"
    },
    {
      "groupId": "com.example",
      "artifactId": "module-b",
      "path": "module-b",
      "reasons": ["TRANSITIVE_DEPENDENCY"],
      "category": "DOWNSTREAM"
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
| `DOWNSTREAM_TEST` | Included as a downstream dependent via test-scoped dependency only |
| `FORCE_BUILD` | This module was force-included via `forceBuildModules` |

**Affected module categories:**

| Category | Description |
|----------|-------------|
| `DIRECT` | Directly affected by a change |
| `UPSTREAM` | Included as an upstream dependency (via `alsoMake`) |
| `DOWNSTREAM` | Included as a downstream dependent (via `alsoMakeDependents`) |

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

## Migrating from GIB (gitflow-incremental-builder)

| GIB Property | Scalpel Equivalent | Notes |
|---|---|---|
| `gib.enabled` | `scalpel.enabled` | Same semantics |
| `gib.referenceBranch` | `scalpel.baseBranch` | Same semantics |
| `gib.disableIfBranchMatches` | `scalpel.disableOnBranch` | Same (regex CSV) |
| `gib.disableIfReferenceBranchMatches` | `scalpel.disableOnBaseBranch` | Same (regex CSV) |
| `gib.fetchReferenceBranch` | `scalpel.fetchBaseBranch` | Same |
| `gib.disableSelectedProjectsHandling` | `scalpel.disableOnSelectedProjects` | Inverted default |
| `gib.logImpactedTo` | `scalpel.impactedLog` | Same format |
| `gib.buildUpstream` | `scalpel.alsoMake` | Boolean (no "derived" mode) |
| `gib.buildDownstream` | `scalpel.alsoMakeDependents` | Boolean |
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

### Example Migration

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

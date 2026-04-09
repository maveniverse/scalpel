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

1. **Find the merge base** between the current HEAD and the configured base branch using JGit.
2. **Detect changed files** by diffing the merge base against HEAD.
3. **Check full-build triggers** — if any changed file matches a trigger pattern (e.g. `.mvn/**`), a full
   build is triggered.
4. **Map source changes to modules** — non-POM file changes are mapped to the owning module by directory
   prefix matching.
5. **Analyze POM changes directly** — for changed `pom.xml` files, the old POM is read from the base commit
   and compared field-by-field. Only modules whose POM actually changed (dependencies, plugins, properties,
   etc.) are marked as affected. Property indirection is resolved: if a parent POM changes a property used
   in a managed dependency version (`${spring.version}`), child modules using that dependency are detected.
6. **Check transitive impact** — modules not directly affected are checked for transitive exposure to changed
   managed dependencies (via dependency resolution) and changed managed plugins.
7. **Apply the selected mode** — trim the reactor, skip tests, or write a report.

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

```
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

```
mvn verify -Dscalpel.baseBranch=origin/main
```

### `skip-tests`

Builds all modules but skips tests on modules that are not affected by changes. Modules affected by
transitive dependency or managed plugin changes also have their tests run. This is useful when you
want a full compile but only want to test what changed.

```
mvn verify -Dscalpel.mode=skip-tests -Dscalpel.baseBranch=origin/main
```

### `report`

Writes a JSON report of affected modules to a file without modifying the reactor. All modules are
built normally. This is useful when a CI script needs Scalpel's change detection results but wants
to control the build flow itself.

```
mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main
```

The report is written to `target/scalpel-report.json` by default (configurable via `scalpel.reportFile`).

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
      "reasons": ["POM_CHANGE"]
    },
    {
      "groupId": "com.example",
      "artifactId": "module-b",
      "path": "module-b",
      "reasons": ["TRANSITIVE_DEPENDENCY"]
    }
  ]
}
```

**Affected module reasons:**

| Reason | Description |
|--------|-------------|
| `SOURCE_CHANGE` | A non-POM file changed in this module's directory |
| `POM_CHANGE` | This module's POM is affected by a POM change (property, dependency, or plugin) |
| `TRANSITIVE_DEPENDENCY` | A changed managed dependency reaches this module transitively |
| `MANAGED_PLUGIN` | This module uses a plugin whose managed version changed |

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
| `scalpel.reportFile` | `target/scalpel-report.json` | Path for the JSON report (report mode), relative to reactor root |
| `scalpel.failSafe` | `true` | On error, fall back to a full build instead of failing |

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

## Full Build Triggers

By default, changes to files under `.mvn/` (e.g. `extensions.xml`, `maven.config`) trigger a full
build. You can customize this with comma-separated glob patterns:

```
-Dscalpel.fullBuildTriggers=.mvn/**,Jenkinsfile,*.gradle
```

> **Note:** Since Scalpel itself lives in `.mvn/extensions.xml`, any change to that file (e.g. a
> Dependabot PR bumping Scalpel's version) will trigger a full build by default. If this is undesired,
> override the triggers to be more specific or set them to empty:
> ```
> -Dscalpel.fullBuildTriggers=
> ```

## Disabling Scalpel

To run a full build without Scalpel:

```
mvn verify -Dscalpel.enabled=false
```

## POM Change Analysis

When a `pom.xml` file changes, Scalpel doesn't blindly mark the module as affected. Instead, it reads
the old POM from the base commit and compares it field-by-field with the current POM. The following
aspects are compared:

* Packaging
* Dependencies and dependency management
* Properties
* Build configuration (plugins, plugin management, source directories)
* Repositories and plugin repositories

This means cosmetic POM changes (reformatting, reordering, adding comments) won't trigger unnecessary
rebuilds.

### Property Indirection

Scalpel resolves property indirection in managed dependencies and plugins. For example, if a parent POM
changes `<kafka.version>3.6.0</kafka.version>` to `<kafka.version>3.7.0</kafka.version>`, and a managed
dependency uses `<version>${kafka.version}</version>`, Scalpel detects that the managed dependency's
effective version changed and marks child modules that use it as affected.

## Git Worktree Support

Scalpel works correctly in git worktrees, where `.git` is a file pointing to the main repository
rather than a directory.

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

# Maveniverse Scalpel

Requirements:
* Java: 8+
* Maven: 3.8.x+

Scalpel is a Maven core extension that detects which modules in a multi-module reactor are affected by
a git changeset and trims the reactor to only build those modules (plus their upstream dependencies and
downstream dependents). This dramatically speeds up CI builds for pull requests and feature branches
by skipping modules that haven't changed.

## How It Works

Scalpel hooks into Maven's lifecycle via `AbstractMavenLifecycleParticipant.afterProjectsRead()` and performs
the following steps:

1. **Find the merge base** between the current HEAD and the configured base branch using JGit.
2. **Detect changed files** by diffing the merge base against HEAD.
3. **Check full-build triggers** — if any changed file matches a trigger pattern (e.g. `.mvn/**`), a full
   build is performed.
4. **Map source changes to modules** — non-POM file changes are mapped to the owning module by directory
   prefix matching.
5. **Compare effective POM models** — for changed `pom.xml` files, the old POM is read from the base commit
   and built into an effective model. Only modules whose effective model actually changed (dependencies,
   plugins, properties, etc.) are marked as affected.
6. **Compute the build set** — upstream dependencies and downstream dependents of affected modules are
   included, then the reactor is trimmed.

The effective model comparison is a key design choice: changing a property in a parent POM that isn't
inherited by a child module won't trigger a rebuild of that child.

## Usage

Add Scalpel to your project's `.mvn/extensions.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<extensions>
    <extension>
        <groupId>eu.maveniverse.maven.scalpel</groupId>
        <artifactId>extension3</artifactId>
        <version>${version.scalpel}</version>
    </extension>
</extensions>
```

Then run Maven as usual. On a feature branch, Scalpel will automatically detect the base branch on
supported CI systems and trim the reactor:

```
$ mvn verify
[INFO] Scalpel 0.1.0-SNAPSHOT activated
[INFO] Scalpel: 3 changed files detected
[INFO] Scalpel: 2 modules directly affected: [com.example:module-a, com.example:module-b]
[INFO] Scalpel: Building 3 of 8 modules: [com.example:parent, com.example:module-a, com.example:module-b]
```

## Configuration

All properties can be set via `-D` on the command line or in `.mvn/maven.config`.

| Property | Default | Description |
|----------|---------|-------------|
| `scalpel.enabled` | `true` | Enable/disable Scalpel |
| `scalpel.baseBranch` | *(auto-detected)* | Base branch to compare against (e.g. `origin/main`) |
| `scalpel.head` | `HEAD` | The commit to compare (usually left as default) |
| `scalpel.alsoMake` | `true` | Include upstream dependencies of affected modules |
| `scalpel.alsoMakeDependents` | `true` | Include downstream dependents of affected modules |
| `scalpel.fullBuildTriggers` | `.mvn/**` | Comma-separated glob patterns; if a changed file matches, a full build is triggered |
| `scalpel.failSafe` | `true` | On error, fall back to a full build instead of failing |

## CI Auto-Detection

Scalpel automatically detects the base branch on common CI systems:

| CI System | Environment Variable |
|-----------|---------------------|
| GitHub Actions | `GITHUB_BASE_REF` |
| GitLab CI | `CI_MERGE_REQUEST_TARGET_BRANCH_NAME` |
| Jenkins | `CHANGE_TARGET` |

If no CI environment is detected and `scalpel.baseBranch` is not set, Scalpel skips trimming
and a full build is performed.

## Full Build Triggers

By default, changes to files under `.mvn/` (e.g. `extensions.xml`, `maven.config`) trigger a full
build. You can customize this with comma-separated glob patterns:

```
-Dscalpel.fullBuildTriggers=.mvn/**,Jenkinsfile,*.gradle
```

## Disabling Scalpel

To run a full build without Scalpel trimming:

```
mvn verify -Dscalpel.enabled=false
```

## Effective Model Comparison

When a `pom.xml` file changes, Scalpel doesn't blindly mark the module as affected. Instead, it builds
the effective model from the old POM (at the merge base) and compares it field-by-field with the current
effective model. The following aspects are compared:

* Packaging
* Dependencies and dependency management
* Properties
* Build configuration (plugins, plugin management, source directories)
* Repositories and plugin repositories

This means cosmetic POM changes (reformatting, reordering, adding comments) won't trigger unnecessary
rebuilds.

Look into ITs for usage examples.

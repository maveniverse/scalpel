# Maveniverse Scalpel

Requirements:
* Java: 8+
* Maven: 3.9.x+

Scalpel is a Maven core extension that detects which modules in a multi-module reactor are affected by a git changeset. It can trim the reactor to only build affected modules, skip tests on unaffected modules, or produce a JSON report of affected modules for consumption by CI scripts.

If Scalpel's behaviour surprises you (it built everything, or it skipped a module you expected to be built), see the [Troubleshooting Guide](docs/troubleshooting.md).

## How It Works

Scalpel hooks into Maven's lifecycle via `AbstractMavenLifecycleParticipant.afterProjectsRead()` and performs the following steps:

1. Check disable conditions (skip if disabled by property, branch match, or `-pl` selection)
2. Fetch base branch (if configured) for CI with shallow clones
3. Find the merge base between HEAD and the configured base branch using JGit
4. Detect changed files by diffing the merge base against HEAD, optionally including uncommitted and untracked files
5. Apply path filters (disable triggers, exclude paths, full-build triggers)
6. Map source changes to modules by directory prefix matching
7. Analyze POM changes directly (read old POM from base commit, compare field-by-field)
8. Check transitive impact (changed managed dependencies and plugins via dependency resolution)
9. Add force-build modules matching configured regex patterns
10. Apply the selected mode (trim reactor, skip tests, or write report)

For deep-dive details on POM analysis, property indirection, import-scope BOM detection, and source-set-aware propagation, see [POM Change Analysis](docs/POM-ANALYSIS.md).

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

Then run Maven as usual. On a feature branch in CI, Scalpel will automatically detect the base branch and trim the reactor:

```text
$ mvn verify
[INFO] Scalpel 0.3.10 activated (mode=trim)
[INFO] Scalpel: 3 changed files detected
[INFO] Scalpel: 2 modules directly affected: [com.example:module-a, com.example:module-b]
[INFO] Scalpel: Building 3 of 8 modules: [com.example:parent, com.example:module-a, com.example:module-b]
```

## Modes

Scalpel supports three modes, selected via `-Dscalpel.mode=<mode>`:

### `trim` (default)

Removes unaffected modules from the reactor. Only affected modules and their upstream or downstream dependencies are built. This is the most aggressive mode.

```bash
mvn verify -Dscalpel.baseBranch=origin/main
```

### `skip-tests`

Builds all modules but skips tests on modules that are not affected by changes. Modules affected by transitive dependency or managed plugin changes also have their tests run. Useful when you want a full compile but only want to test what changed.

```bash
mvn verify -Dscalpel.mode=skip-tests -Dscalpel.baseBranch=origin/main
```

### `report`

Writes a JSON report of affected modules to a file without modifying the reactor. All modules are built normally. Useful when a CI script needs Scalpel's change detection results but wants to control the build flow itself.

```bash
mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main
```

The report is written to `target/scalpel-report.json` by default. See [Report Format](docs/REPORT-FORMAT.md) for the schema and all fields.

## Impacted Module Log

For CI scripts that need a simple flat file (e.g. for GitHub Actions matrix filtering), Scalpel can write a list of directly impacted module paths, one per line:

```bash
mvn validate -Dscalpel.mode=report -Dscalpel.impactedLog=target/scalpel-impacted.log
```

Example output:

```text
module-a
module-b/sub-module
```

This works in all modes and is written alongside the JSON report. It contains only directly affected modules (not upstream or downstream).

## Configuration

Scalpel is configured via properties (command-line `-D` or `.mvn/maven.config`). Property precedence follows Maven convention: user properties win over system properties, which win over defaults.

See [Configuration Reference](docs/CONFIGURATION.md) for the complete property table, CI auto-detection, local development usage, path filtering, and all options.

## CI Recipes

Worked examples for GitHub Actions, GitLab CI, and Jenkins pipelines are in [CI Recipes](docs/CI-RECIPES.md).

## Migrating from GIB

If you are migrating from gitflow-incremental-builder (GIB), see [GIB Migration Guide](docs/GIB-MIGRATION.md) for a detailed comparison, property mapping, and migration example.

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

Look into integration tests for more usage examples.

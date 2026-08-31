# Scalpel Report Format

Scalpel's `report` mode writes a JSON report of affected modules to a file without modifying the reactor. All modules are built normally. This is useful when a CI script needs Scalpel's change detection results but wants to control the build flow itself.

## Usage

```bash
mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main
```

The report is written to `target/scalpel-report.json` by default (configurable via `-Dscalpel.reportFile=<path>`).

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

This works in all modes (`trim`, `skip-tests`, `report`) and is written alongside the JSON report, not as a replacement. It contains only directly affected modules (not upstream or downstream).

## Schema

The report follows a versioned JSON schema. A [JSON Schema](../core/src/main/resources/eu/maveniverse/maven/scalpel/core/scalpel-report-v2.schema.json) is published alongside the code. The current version is **2**.

### Example Report

```json
{
  "version": "2",
  "scalpelVersion": "0.3.10",
  "baseBranch": "origin/main",
  "fullBuildTriggered": false,
  "triggerFile": null,
  "changedFiles": ["module-a/src/main/java/Foo.java", "module-b/src/test/java/BarTest.java", "gated-module/pom.xml"],
  "changedProperties": [],
  "changedManagedDependencies": [],
  "changedManagedPlugins": [],
  "unmatchedPomPaths": ["gated-module/pom.xml"],
  "excludedUpstreamCount": 0,
  "affectedModules": [
    {
      "groupId": "com.example",
      "artifactId": "module-a",
      "path": "module-a",
      "reasons": ["SOURCE_CHANGE"],
      "category": "DIRECT",
      "sourceSet": "main",
      "evidence": ["module-a/src/main/java/Foo.java"]
    },
    {
      "groupId": "com.example",
      "artifactId": "module-b",
      "path": "module-b",
      "reasons": ["TEST_CHANGE"],
      "category": "DIRECT",
      "sourceSet": "test"
    },
    {
      "groupId": "com.example",
      "artifactId": "module-c",
      "path": "module-c",
      "reasons": ["DOWNSTREAM_DEPENDENT"],
      "testsSkipped": true,
      "testsSkippedReason": "EXCLUDED_DOWNSTREAM"
    }
  ],
  "skippedModules": [
    {
      "groupId": "com.example",
      "artifactId": "module-d",
      "path": "module-d",
      "reason": "NOT_AFFECTED"
    }
  ]
}
```

## Top-Level Fields

| Field | Type | Description |
|-------|------|-------------|
| `version` | string | Schema version (`"2"`) |
| `scalpelVersion` | string | Scalpel version that generated this report |
| `baseBranch` | string | The base branch used for change detection |
| `fullBuildTriggered` | boolean | `true` if a full build was triggered (e.g., by `fullBuildTriggers`) |
| `triggerFile` | string | Path of the file that triggered a full build (if applicable) |
| `changedFiles` | string[] | List of changed files (relative to reactor root) |
| `changedProperties` | string[] | List of property names that changed in POMs |
| `changedManagedDependencies` | string[] | List of changed managed dependency GAVs |
| `changedManagedPlugins` | string[] | List of changed managed plugin GAVs |
| `unmatchedPomPaths` | string[] | Changed POMs that match no reactor project (profile-gated or `-pl`-excluded; their changes are ignored, with a warning) |
| `excludedUpstreamCount` | integer | Number of upstream build-prerequisite modules excluded from `affectedModules` |
| `affectedModules` | object[] | Modules included in the build set (see below) |
| `skippedModules` | object[] | Reactor modules left out of the build set (see below) |
| `status` | string | *(optional)* Only present when analysis did not complete: `"failed"` or `"skipped"` |
| `reason` | string | *(optional)* Human-readable explanation when `status` is present |

## Module Object Fields

Each module in `affectedModules` or `skippedModules` contains:

| Field | Type | Description |
|-------|------|-------------|
| `groupId` | string | Module's groupId |
| `artifactId` | string | Module's artifactId |
| `path` | string | Module directory path (relative to reactor root) |
| `reasons` | string[] | *(affectedModules only)* List of reason codes (see below) |
| `category` | string | *(affectedModules only)* One of: `DIRECT`, `TRANSITIVE`, `UPSTREAM`, `DOWNSTREAM` |
| `sourceSet` | string | *(optional)* Present only on directly affected modules with source changes: `"main"` or `"test"` |
| `evidence` | string[] | *(optional)* Explain-mode inputs behind this module's decision (requires `-Dscalpel.explain=true`) |
| `testsSkipped` | boolean | *(optional)* Present and `true` when tests are skipped for this module |
| `testsSkippedReason` | string | *(optional)* Why tests were skipped (see values below) |
| `reason` | string | *(skippedModules only)* Why the module was skipped: `"NOT_AFFECTED"` |

## Affected Module Reasons

| Reason | Description |
|--------|-------------|
| `SOURCE_CHANGE` | A non-POM, non-test file changed in this module's directory |
| `TEST_CHANGE` | Only test source files (`src/test/**`) changed; production artifact is unchanged |
| `POM_CHANGE` | This module's POM is affected by a POM change (property, dependency, or plugin) |
| `TRANSITIVE_DEPENDENCY` | A changed managed dependency reaches this module transitively (compile or runtime scope) |
| `TRANSITIVE_DEPENDENCY_TEST` | A changed managed dependency reaches this module transitively via test scope only |
| `TRANSITIVE_DEPENDENCY_UNRESOLVED` | Dependency resolution failed; conservatively treated as affected (genuine transitive change versus resolution failure is indistinguishable) |
| `MANAGED_PLUGIN` | This module uses a plugin whose managed version changed |
| `DOWNSTREAM_DEPENDENT` | Included as a downstream dependent (via `alsoMakeDependents`) |
| `DOWNSTREAM_TEST` | Included as a downstream dependent via test-scoped dependency only |
| `FORCE_BUILD` | This module was force-included via `forceBuildModules` |

Upstream build-prerequisite modules are not listed with a reason; they are excluded from `affectedModules` and only counted in `excludedUpstreamCount`.

## Affected Module Source Sets

| Source Set | Description |
|------------|-------------|
| `main` | Main source files (`src/main/**`) changed; all downstream dependents are affected |
| `test` | Only test source files (`src/test/**`) changed; only test-jar dependents are affected |

## Affected Module Categories

| Category | Description |
|----------|-------------|
| `DIRECT` | Directly affected by a change |
| `TRANSITIVE` | Affected by a changed managed dependency or plugin (not directly changed) |
| `UPSTREAM` | Included as an upstream dependency (via `alsoMake`) |
| `DOWNSTREAM` | Included as a downstream dependent (via `alsoMakeDependents`) |

## Test-Skip Reasons

| Value | Description |
|-------|-------------|
| `EXCLUDED_DOWNSTREAM` | The module is a downstream dependent that matched `skipTestsForDownstreamModules`, so it is built without tests |

## Schema Version History

| Version | Changes |
|---------|---------|
| `2` | Added `category`, `sourceSet`, `excludedUpstreamCount`, `testsSkipped`, `testsSkippedReason`. Later additive (no bump): `status`, `reason`, `unmatchedPomPaths`, `skippedModules`, `evidence` |
| `1` | Initial schema |

## Compatibility Policy

Within a schema version, Scalpel may add new optional fields and new enum values; consumers must tolerate both (ignore unknown fields, treat unrecognized enum values as unknown). A version bump is required when a change breaks such consumers: removing or renaming a field, changing a field's type, making an optional field required, or removing an enum value that the schema ever declared and could have been emitted.

Removing an enum value that no released schema ever carried and that the code no longer emits does not break any consumer and needs no bump.

Reports are validated against the checked-in schema by `core`'s unit tests, guarding required fields and enum values against drift in both directions; a newly emitted optional field still requires a deliberate schema edit, which the drift guard surfaces through the enum and required-field checks.

# Scalpel Configuration

You can set all properties via `-D` on the command line or in `.mvn/maven.config`.

## Property Precedence

Property precedence follows the Maven convention. User properties from the command line win over system properties. System properties win over documented defaults. A `-Dscalpel.mode=trim` on the command line always wins over a `MAVEN_OPTS=-Dscalpel.mode=report`.

Properties defined in a project POM are deliberately not read. Scalpel is configured only from session properties. This prevents pull requests from reconfiguring it through `pom.xml`.

## Configuration Properties

| Property | Default | Description |
|----------|---------|-------------|
| `scalpel.enabled` | `true` | Enable or disable Scalpel |
| `scalpel.baseBranch` | auto-detected | Base branch to compare against (e.g. `origin/main`) |
| `scalpel.head` | `HEAD` | The commit to compare (usually left as default) |
| `scalpel.mode` | `trim` | Operating mode. Use `trim`, `skip-tests`, or `report` |
| `scalpel.alsoMake` | `true` | Include upstream dependencies of affected modules (trim mode) |
| `scalpel.alsoMakeDependents` | `true` | Include downstream dependents of affected modules (trim mode) |
| `scalpel.fullBuildTriggers` | `.mvn/**` | Comma-separated glob patterns. If a changed file matches, a full build is triggered |
| `scalpel.excludePaths` | none | Comma-separated glob patterns. Changed files matching these are ignored |
| `scalpel.includePaths` | none | Comma-separated glob patterns. Narrows the affected set to matching modules |
| `scalpel.disableTriggers` | none | Comma-separated glob patterns. If any changed file matches, Scalpel is disabled |
| `scalpel.reportFile` | `target/scalpel-report.json` | Path for the JSON report, relative to reactor root (written in all modes) |
| `scalpel.impactedLog` | none | Write impacted module paths to this file (one per line; paths outside the safe character set are skipped with a WARN) |
| `scalpel.forceBuildModules` | none | Comma-separated regex patterns. Always include modules whose artifactId matches |
| `scalpel.buildAllIfNoChanges` | `false` | Build everything when no changes are detected (useful for cron builds) |
| `scalpel.disableOnBranch` | none | Comma-separated regex patterns. Disable if current branch matches |
| `scalpel.disableOnBaseBranch` | none | Comma-separated regex patterns. Disable if base branch matches |
| `scalpel.disableOnSelectedProjects` | `false` | Disable Scalpel when `-pl` is used |
| `scalpel.skipTestsForUpstream` | `false` | Skip tests on modules included only as upstream dependencies |
| `scalpel.skipTestsForDownstreamModules` | none | Comma-separated glob patterns. Skip tests on downstream modules whose path matches |
| `scalpel.upstreamArgs` | none | Comma-separated `key=value` properties to set on upstream-only modules |
| `scalpel.downstreamArgs` | none | Comma-separated `key=value` properties to set on downstream-only modules |
| `scalpel.fetchBaseBranch` | `false` | Fetch base branch from remote before change detection |
| `scalpel.uncommitted` | `false` | Include uncommitted (staged plus unstaged) changes |
| `scalpel.untracked` | `false` | Include untracked files in change detection |
| `scalpel.failSafe` | `true` | On error, fall back to a full build instead of failing |
| `scalpel.maxResourceFileSize` | `10 MB` | Maximum size in bytes for a resource file (resources larger than this are skipped with a warning) |
| `scalpel.explain` | `false` | Enable explain mode. This adds per-module decision evidence to the report |

## Local Developer Usage

By default, Scalpel only considers committed changes. For local development, you can include uncommitted and/or untracked files.

```text
# In .mvn/maven.config for local dev:
-Dscalpel.uncommitted=true
-Dscalpel.untracked=true
```

This detects staged, unstaged, and new files without requiring a commit. CI environments should leave these as `false` (the default).

## CI Auto-Detection

Scalpel automatically detects the base branch on common CI systems.

| CI System | Environment Variable |
|-----------|---------------------|
| GitHub Actions | `GITHUB_BASE_REF` |
| GitLab CI | `CI_MERGE_REQUEST_TARGET_BRANCH_NAME` |
| Jenkins | `CHANGE_TARGET` |

If no CI environment is detected and `scalpel.baseBranch` is not set, Scalpel is a no-op. All modules are built normally. This makes it safe to keep Scalpel in `.mvn/extensions.xml` permanently.

### Fetching the Base Branch

In CI environments with shallow clones or forks, the base branch may not exist locally. Scalpel can fetch it automatically.

```bash
-Dscalpel.fetchBaseBranch=true
```

This parses the base branch reference to extract the remote and branch name, then fetches only that ref.

## Full Build Triggers

By default, changes to files under `.mvn/` trigger a full build. You can customize this.

```bash
-Dscalpel.fullBuildTriggers=.mvn/**,Jenkinsfile,*.gradle
```

**Note:** Since Scalpel itself lives in `.mvn/extensions.xml`, any change to that file will trigger a full build by default. If this is undesired, override the triggers.

```bash
-Dscalpel.fullBuildTriggers=
```

**Note:** Files at the repository root that are not part of any module (e.g. `lombok.config`) are treated as triggering a full build. If you want to ignore them, add them to `excludePaths`.

```bash
-Dscalpel.excludePaths=lombok.config
```

## Path Filtering

Scalpel provides three file-level filters over change detection, plus a module-level scope.

- **`scalpel.excludePaths`** ignores matching files from change detection. Use this for files that should not trigger rebuilds.

```bash
-Dscalpel.excludePaths=*.md,LICENSE,.sdkmanrc,.editorconfig
```

- **`scalpel.fullBuildTriggers`** forces a full build if any changed file matches.

- **`scalpel.disableTriggers`** disables Scalpel entirely if any changed file matches.

```bash
-Dscalpel.disableTriggers=.github/**
```

These filters are applied in order. Disable triggers are checked first, then excluded paths are removed, then full build triggers are checked.

Separately, **`scalpel.includePaths`** scopes modules rather than files. It narrows the affected set to modules whose path matches.

```bash
-Dscalpel.includePaths=frontend/**,shared/**
```

Because it acts on modules, change detection still sees the whole diff. `includePaths` narrows the affected set, and each mode then applies its usual upstream and downstream rules.

Patterns are matched against the module directory or its `pom.xml` path. Use a trailing `/**` to cover a module together with its submodules.

## Force-Build Modules

Some modules should always be built regardless of change detection. Use `forceBuildModules` with regex patterns.

```bash
-Dscalpel.forceBuildModules=.*-it,.*-tests
```

Patterns use full-match Java regex semantics against the module artifact ID. Inputs longer than 256 characters are never matched. Keep patterns linear. Avoid nested quantifiers.

For scheduled or cron builds, use `buildAllIfNoChanges` to fall back to a full build.

```bash
-Dscalpel.buildAllIfNoChanges=true
```

## Disabling Scalpel

To run a full build without Scalpel:

```bash
mvn verify -Dscalpel.enabled=false
```

### Branch-Based Disable

Scalpel can automatically disable itself based on the current or base branch name.

```bash
# Disable on main and release branches
-Dscalpel.disableOnBranch=main,release/.*

# Disable when the base branch is a maintenance branch
-Dscalpel.disableOnBaseBranch=\d+\.\d+
```

Branch patterns are Java regular expressions. For `disableOnBaseBranch`, the remote prefix is stripped before matching.

### Selected Projects Handling

By default, when Maven is invoked with `-pl`, Scalpel trims within the `-pl` scope. In CI downstream test jobs, you may want to disable Scalpel.

```bash
mvn verify -pl integration-tests/maven -Dscalpel.disableOnSelectedProjects=true
```

## Upstream and Downstream Module Handling

When Scalpel detects affected modules, it includes upstream dependencies and downstream dependents in the build set.

**Skip tests on upstream modules:**

```bash
mvn verify -Dscalpel.skipTestsForUpstream=true
```

**Skip tests on downstream modules (by path pattern):**

```bash
-Dscalpel.skipTestsForDownstreamModules=deployables/**
```

**Inject properties per category:**

```bash
mvn verify -Dscalpel.upstreamArgs=skipITs=true -Dscalpel.downstreamArgs=skipITs=true
```

In `report` mode, each affected module in the JSON report includes a `category` field (`DIRECT`, `UPSTREAM`, or `DOWNSTREAM`).

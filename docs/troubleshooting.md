# Troubleshooting Guide

Every Scalpel adopter eventually asks one of two questions: *why did Scalpel build everything?*
and *why did Scalpel skip my module?* This guide answers both by pointing at the exact log line
or JSON report field that identifies each cause. Log lines below are quoted as the code emits
them; all start with `Scalpel` or `Scalpel:`, so a quick filter shows you where you stand:

```bash
mvn verify 2>&1 | grep Scalpel
```

## First: confirm Scalpel actually ran

The first thing to look for is the activation line:

```text
[INFO] Scalpel 0.3.10 activated (mode=trim)
```

If it is missing, Scalpel never started and the build ran without it (full build, no report).
Three lines explain that:

* `Scalpel 0.3.10 is disabled`: `scalpel.enabled=false` is set somewhere (command line,
  `.mvn/maven.config`, or `MAVEN_OPTS`).
* `Scalpel 0.3.10 disabled due to -pl project selection`: Maven was invoked with `-pl` and
  `scalpel.disableOnSelectedProjects=true`.
* `Scalpel: Error parsing configuration, building all modules: <message>` (WARN): a
  configuration property had an invalid value (for example a boolean property set to something
  other than `true`/`false`, or an unknown `scalpel.mode`). Scalpel deliberately refuses to
  guess and lets the build proceed unchanged.

Also check for unknown-key warnings such as
`Unknown configuration key 'scalpel.fullBuildTrigger'. Did you mean 'scalpel.fullBuildTriggers'?`
(WARN). A typo'd property name is ignored otherwise; this warning is how you catch it.

## Why did Scalpel build everything?

Work through this list in order. It follows the order in which Scalpel itself evaluates these
conditions, so the first cause whose log line you recognize is the one that applied.

### No base branch configured or detected

```text
[INFO] Scalpel: No base branch configured or detected, building all modules
```

Scalpel found no base branch to diff against. Auto-detection only works on CI systems that
export `GITHUB_BASE_REF`, `CI_MERGE_REQUEST_TARGET_BRANCH_NAME`, or `CHANGE_TARGET`; on a
local build, or a CI without those variables, nothing is detected.

Fix: set the base branch explicitly:

```bash
mvn verify -Dscalpel.baseBranch=origin/main
```

In `report` mode the report file confirms this with `"status": "skipped"` and
`"reason": "no base branch configured"`.

### Not a git repository

```text
[INFO] Scalpel: Not a git repository, building all modules
```

The reactor root is not inside a git working tree (or `.git` cannot be resolved, which also
happens in some exported source archives). Report equivalent: `"status": "skipped"`,
`"reason": "not a git repository"`.

### No merge base (typical in shallow clones)

```text
[WARN] Cannot resolve base branch: origin/main
[WARN] No merge base found between origin/main and HEAD
[WARN] Cannot compute merge base between origin/main and HEAD: commit history is incomplete (shallow clone or missing objects). ...
[WARN] Scalpel: Could not find merge base between origin/main and HEAD, building all modules
```

The last line is Scalpel's summary; the earlier ones come from the git layer and say which ref
was the problem. The most common cause is a CI shallow clone (`--depth 1`) that does not
contain the history shared with the base branch.

Fixes:

* Enable `-Dscalpel.fetchBaseBranch=true` so Scalpel fetches the base ref before diffing. If
  the fetch itself fails you get `Scalpel: Failed to fetch origin/main, building all modules: ...`
  instead; check the CI's git credentials.
* Deepen the clone (`git fetch --unshallow` or a larger `--depth`) so the merge base exists.
* Set `-Dscalpel.failSafe=false` to make Scalpel fail the build loudly instead of falling back
  to a full build.

On this path (`failSafe=true`, the default) the report file is overwritten with
`"status": "failed"` and `"reason": "change detection did not run (see build log)"`. Any
`"failed"` status means: do not trust the report contents, read the build log.

### A failSafe bail-out

```text
[WARN] Scalpel: Failed to fetch origin/main, building all modules: ...
[WARN] Scalpel: Error analyzing POM changes, building all modules: ...
[WARN] Scalpel: Unexpected error, building all modules: ...
```

Something went wrong and `scalpel.failSafe=true` (the default) turned the error into a full
build rather than a red build. Re-run with `-X` to get the stack trace (Scalpel logs exception
details at debug level) and with `-Dscalpel.failSafe=false` if you want the failure to surface
directly. In `report` mode, each of these paths overwrites the report with a `"failed"`
status document.

### Scalpel disabled itself on purpose

```text
[INFO] Scalpel: Disabled because current branch 'main' matches pattern 'main'
[INFO] Scalpel: Disabled because base branch 'origin/main' matches pattern 'release/.*'
[INFO] Scalpel: Disabled due to change in .github/workflows/ci.yml (matches disable trigger .github/**)
```

These are your own `scalpel.disableOnBranch`, `scalpel.disableOnBaseBranch`, and
`scalpel.disableTriggers` patterns doing exactly what they were configured to do. This is a
deliberate full build; in `report` mode the status document says `"skipped"` with the matching
reason (for example `"disabled by disableOnBranch"` or `"disabled by disableTriggers match"`).

### A full-build trigger matched

```text
[INFO] Scalpel: Full build triggered by change to .mvn/extensions.xml (matches .mvn/**)
```

A changed file matched a `scalpel.fullBuildTriggers` pattern. The default is `.mvn/**`, which
is why any change to `extensions.xml` or `maven.config` (a Dependabot version bump of Scalpel
included) builds everything. In `report` mode the report records it as
`"fullBuildTriggered": true` with the `"triggerFile"`.

Fix: narrow the patterns or disable them, if you accept the consequences:

```bash
mvn verify -Dscalpel.fullBuildTriggers=
```

### The diff was empty

```text
[INFO] Scalpel: No changes detected between origin/main and HEAD
```

The merge-base diff is empty, so there is nothing to trim and the reactor is left untouched
(all modules build). This is expected on a branch with no commits ahead of the base; if you
expected uncommitted work to count, enable `-Dscalpel.uncommitted=true` and/or
`-Dscalpel.untracked=true`. With `buildAllIfNoChanges=true` you additionally get
`Scalpel: No changes detected, building all modules (buildAllIfNoChanges=true)`. In `report`
mode the status document says `"skipped"` / `"no changes detected"`.

### Every changed file was excluded

```text
[INFO] Scalpel: 3 files excluded by path filters
[INFO] Scalpel: All changed files excluded by path filters, building all modules
```

All changed files matched `scalpel.excludePaths`, so no module was affected and the reactor is
left untouched. If you expected one of those files to matter, check the glob semantics
described in [your changed files were excluded](#your-changed-files-were-excluded) below.
Report equivalent: `"status": "skipped"`,
`"reason": "all changed files excluded by path filters"`.

### Nothing mapped to a module

```text
[INFO] Scalpel: 1 changed files detected
[INFO] Scalpel: No modules affected by changes
```

There were changes, but none inside a module directory. For example a bare root-level file
like `README.md` maps to no module: the root aggregator only picks up files in subdirectories
of the reactor root. Nothing is trimmed and the full reactor builds. Add such files to
`scalpel.excludePaths` if you want them out of the diff, or ignore this case: building
everything when nothing is affected is the safe outcome.

### The build set really is the whole reactor

Sometimes Scalpel did trim, and the answer is in *why* each module is in the set:

```text
[INFO] Scalpel: Building 52 of 52 modules: [com.example:parent, com.example:module-a, ...]
```

Three recurring shapes:

1. **You changed a module that everything depends on.** A change to a core library genuinely
   affects all its dependents, and with `alsoMakeDependents=true` (the default) they are all
   included. The report shows them as `DOWNSTREAM_DEPENDENT`. This is correct behavior; if
   you accept the risk you can scope it with `-Dscalpel.alsoMakeDependents=false` or
   `scalpel.includePaths`.

2. **A hub / sync-point module pulled the reactor in as upstream.** A module that *depends on*
   everything (aggregator-style "all components" modules) drags the whole reactor into the
   build set through `alsoMake=true` (the default) the moment it is affected or included.
   Signals: the log line
   `Scalpel: 31 upstream build-prerequisite modules excluded from report (use trim/skip-tests mode for full build set)`,
   a large `excludedUpstreamCount` in the report, and, with `-Dscalpel.explain=true`, many
   modules justified as `upstream of com.example:hub-module`. Fixes:
   `-Dscalpel.alsoMake=false`, or scope the run with `scalpel.includePaths`.

3. **A root-level file changed.** A changed file in a directory of the reactor root that is
   not a module (`docs/guide.md`, `.github/workflows/ci.yml`) maps to the root aggregator
   module, marks it `DIRECT` (`SOURCE_CHANGE`), and cascades every reactor module as
   `DOWNSTREAM`. Only `.mvn/**` is exempted from this mapping (those files go through the
   full-build trigger instead). Fixes: `scalpel.excludePaths` or `scalpel.disableTriggers` for
   the directories that should never affect the build.

Run with `-Dscalpel.explain=true` to see, per module, the specific file or relationship that
put it in the build set (`Scalpel explain: BUILD com.example:module-b because: ...`).

## Why did Scalpel skip my module?

### Where to look first

Two places give a per-module answer:

* The JSON report (`target/scalpel-report.json` by default): a module that was left out of the
  build appears in `skippedModules` with `"reason": "NOT_AFFECTED"`. The report is written in
  `trim` and `report` modes; `skip-tests` mode does not write one.
* Explain mode: `mvn verify -Dscalpel.explain=true` logs one line per reactor module:
  `Scalpel explain: SKIP com.example:module-c (not affected by changeset)`.

Then check which of the following applies.

### Your changed files were excluded

```text
[INFO] Scalpel: 2 files excluded by path filters
```

The files matched `scalpel.excludePaths` and were removed from change detection before module
mapping. Glob semantics matter here: a bare pattern (no `/`) matches at any depth, so `*.md`
matches both `README.md` and `docs/guide.md`; a pattern containing `/` is anchored to the
repository root, so `docs/*.md` does not match `docs/sub/page.md` (use `docs/**/*.md`).

### includePaths scoped the build away

```text
[INFO] Scalpel: 3 modules excluded by includePaths filters
[INFO] Scalpel: No modules match includePaths filters
```

`scalpel.includePaths` restricts which *modules* are treated as affected (change detection
still sees the whole diff). A module whose path does not match any pattern is dropped, and in
`trim` mode it can still re-enter only as an upstream build prerequisite. Matching rules:
the pattern is tested against the module's reactor-relative path and against that path plus
`pom.xml`. A bare `module-a` matches only that module, not its submodules; use `module-a/**`
to cover the subtree. If no module matches at all, `trim` falls back to a full build and
`skip-tests` skips tests everywhere.

### Your changed POM matched no reactor project

```text
[WARN] Scalpel: 1 changed POM(s) match no reactor project (profile-gated, excluded by -pl, or module removed); their changes are ignored: [module-x/pom.xml]
```

The diff contains a `pom.xml` that is not part of the current reactor: the module is hidden
behind an inactive profile, cut out by `-pl`, or was deleted on this branch. Its changes are
ignored on purpose. The report lists the paths in `unmatchedPomPaths` (present only when
non-empty, and only in `report` mode).

### Your POM change was cosmetic

Scalpel compares the old and new POM field-by-field (dependencies, plugins, properties,
repositories, resources; plugin configuration is compared as a DOM tree). Reformatting,
reordering, and comment-only changes mark no module as affected. If `changedFiles` in the
report contains your `pom.xml`, the module is absent from `affectedModules`, and it is not in
`unmatchedPomPaths`, then the diff was semantically empty. With `-X` you can see the
comparison outcome per module
(`Child com.example:module-a is NOT affected by parent com.example:parent change`).

Note the flip side: when the old POM cannot be read or parsed, Scalpel is conservative and
marks the module and all its dependents as affected (`Cannot parse old POM for
com.example:parent, marking all dependents as affected`). That is a deliberate
over-approximation, not evidence that your change was meaningful.

### The upstream change was test-only

When a module's changes touch only `src/test/**`, its production artifact is unchanged, so
only dependents that declare a `<type>test-jar</type>` dependency on it are rebuilt. The
directly affected module shows `"sourceSet": "test"` in the report; your module, which depends
on the main artifact, shows up in `skippedModules` as `NOT_AFFECTED`. If you consumed the
test-jar you would have been included as `DOWNSTREAM_TEST`.

### The module was built, but its tests were skipped

In `skip-tests` mode (and for downstream modules when `scalpel.skipTestsForDownstreamModules`
is configured), a module can be built without running its tests:

```text
[INFO] Scalpel: Testing 4, 1 compile-only (test-jar producers), skipping tests on 12 of 17 modules: [com.example:module-d, ...]
```

The report marks such modules with `"testsSkipped": true` and a `testsSkippedReason`:

* `EXCLUDED_DOWNSTREAM`: the module matched `scalpel.skipTestsForDownstreamModules` (entries
  are artifactIds or `groupId:artifactId` coordinates) and its effective model did not change.
  That last guard is a safety check: a module whose plugins or dependency tree actually changed
  always runs its tests.
* Upstream-only modules can be silenced with `scalpel.skipTestsForUpstream=true`.

A related log line explains the odd case of a "skipped" module still compiling its tests:
`Scalpel: 1 modules had test-compile restored for in-reactor test-jar consumers: [...]`.
Those modules' test-jars are needed by modules that do run tests, so test-compile is kept and
only test *execution* is skipped.

## Debugging tools

### Verbose logging

Scalpel logs its decisions at INFO and the reasoning behind them at DEBUG. Two ways to get
the debug level:

* `mvn -X verify`: everything at debug, including Scalpel's full parsed configuration
  (`Configuration: ScalpelConfiguration{enabled=true, baseBranch='origin/main', ...}`), the
  merge base commit, the changed-file list, and per-module mapping decisions.
* Maven's SLF4J provider honors the `org.slf4j.simpleLogger.*` properties, so you can raise
  only Scalpel's loggers without the rest of the debug noise. It must be a JVM system
  property, so put it in `MAVEN_OPTS` or `.mvn/jvm.config`:

  ```bash
  MAVEN_OPTS='-Dorg.slf4j.simpleLogger.log.eu.maveniverse.maven.scalpel=debug' mvn verify
  ```

### Explain mode

```bash
mvn verify -Dscalpel.explain=true
```

Adds one line per reactor module stating the decision and the specific input behind it:

```text
[INFO] Scalpel explain: BUILD com.example:module-a because: module-a/src/main/java/Foo.java
[INFO] Scalpel explain: BUILD com.example:module-b because: downstream of com.example:module-a
[INFO] Scalpel explain: SKIP com.example:module-c (not affected by changeset)
```

The same evidence strings (changed file paths, `effective dep <ga>`, `effective plugin <ga>`,
`own pom <path> changed`, `upstream of`/`downstream of <ga>`, `forced by forceBuildModules
pattern <regex>`, and so on) are attached to each entry of `affectedModules` in the JSON
report as the `evidence` array.

### The JSON report

Written to `target/scalpel-report.json` (configurable via `scalpel.reportFile`) in `trim` and
`report` modes. Fields that answer the two questions:

| Field | Answers |
|-------|---------|
| `status` / `reason` | Present only on status-only documents: `"skipped"` for deliberate non-analysis (no base branch, no changes, disabled, all files excluded), `"failed"` for failSafe bail-outs. A `"failed"` report means "read the build log", not "trust this report". |
| `fullBuildTriggered` / `triggerFile` | Which file forced the full build (`.mvn/**` by default). |
| `changedFiles` | The exact diff Scalpel worked from; the first thing to check when the result surprises you. |
| `affectedModules[].reasons` | Why each module is in the set (`SOURCE_CHANGE`, `TEST_CHANGE`, `POM_CHANGE`, `TRANSITIVE_DEPENDENCY`, `MANAGED_PLUGIN`, `DOWNSTREAM_DEPENDENT`, ...). |
| `affectedModules[].evidence` | The specific inputs behind each decision (requires `-Dscalpel.explain=true`). |
| `affectedModules[].testsSkipped` / `testsSkippedReason` | Built but not tested (`EXCLUDED_DOWNSTREAM`). |
| `skippedModules[]` | Modules left out of the build, each with `"reason": "NOT_AFFECTED"`. |
| `unmatchedPomPaths` | Changed POMs that matched no reactor project and were ignored. |
| `excludedUpstreamCount` | How many upstream build-prerequisite modules were excluded from `affectedModules`; a large number hints at a hub/sync-point module pulling the reactor. |

### The impacted log

```bash
mvn verify -Dscalpel.impactedLog=target/scalpel-impacted.log
```

Writes the affected modules' paths, one per line, in all modes; a simpler input for CI matrix
filtering than the JSON report. It lists direct and transitive affected modules, never the
upstream/downstream expansion.

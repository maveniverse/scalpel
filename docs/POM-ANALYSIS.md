# POM Change Analysis

Scalpel performs deep semantic analysis of POM changes to avoid unnecessary rebuilds. When a `pom.xml` file changes, Scalpel does not blindly mark the module as affected. Instead, it reads the old POM from the base commit and compares it field-by-field with the current POM.

## Compared Aspects

Scalpel compares the following aspects of POMs:

* Packaging type
* Dependencies and dependency management
* Properties
* Build configuration (plugins, plugin management, plugin executions)
* Source directories (`sourceDirectory`, `testSourceDirectory`, `scriptSourceDirectory`)
* Resource and test resource configuration (directory, targetPath, includes, excludes, filtering)
* Repositories and plugin repositories (id, url, layout, snapshot or release policies)
* Active profile sections (properties, dependencies, plugins within active profiles)

This means cosmetic POM changes (reformatting, reordering, adding comments) will not trigger unnecessary rebuilds. Plugin configurations are compared semantically (Xpp3Dom structure), so whitespace-only changes in plugin XML configuration are ignored.

## Profile Awareness

Scalpel only considers changes inside profiles that are currently active. Changes to inactive profiles are ignored, preventing unnecessary rebuilds when modifying profiles that do not apply to the current build.

## Import-Scope BOM Detection

Scalpel detects managed dependency changes in reactor modules that are imported as BOMs via `<scope>import</scope>` in another module's `<dependencyManagement>`. When a BOM module's managed dependencies change, Scalpel propagates those changes to all importing modules. This works just as it does for parent-inherited managed dependencies.

For example, given this reactor layout:

```text
parent/
├── bom/          (defines managed deps: commons-lang ${lib.version})
├── module-a/     (imports bom via <scope>import</scope>, uses commons-lang)
└── module-b/     (no BOM import)
```

If `bom/pom.xml` changes `lib.version` from `3.12` to `3.14`, Scalpel detects that:

* `module-a` is directly affected (reason `POM_CHANGE`, category `DIRECT`): the BOM change reaches it through the import, because it uses `commons-lang`
* `module-b` is not affected (it does not import the BOM or use the dependency)

This works with all POM analysis features. Property indirection, managed plugin changes, and transitive dependency checking all apply.

## Property Indirection

Scalpel resolves property indirection in managed dependencies and plugins. For example, if a parent POM changes `<kafka.version>3.6.0</kafka.version>` to `<kafka.version>3.7.0</kafka.version>`, and a managed dependency uses `<version>${kafka.version}</version>`, Scalpel detects that the managed dependency's effective version changed and marks child modules that use it as affected.

## Resource Filtering

When a property changes in a parent POM, Scalpel checks child modules that have resource filtering enabled (`<filtering>true</filtering>`). If any filtered resource file contains a `${property}` reference to the changed property, the module is marked as affected.

This ensures that changes to properties used in filtered resources (e.g. configuration files with `${app.version}`) trigger a rebuild of the affected module.

## Source-Set-Aware Downstream Propagation

Scalpel distinguishes between main source changes (`src/main/`) and test-only changes (`src/test/`) when propagating changes to downstream modules.

### Test-only changes

When only test sources (`src/test/`) change in a module, the module itself is built and tested (DIRECT), but only downstream modules that declare a `<type>test-jar</type>` dependency on it are included. Regular dependents are NOT affected, since the main artifact is unchanged.

### Main source changes

When main sources (`src/main/` with or without `src/test/`) change in a module, all downstream dependents (regular and test-jar) are included, as the main artifact may have changed.

### POM or resource changes

POM or resource changes are treated like main source changes. All dependents are included.

### Impact

This dramatically reduces unnecessary builds. For example, in Apache Camel, `camel-core` has 518
transitive dependents (52 of them direct) but only 25 modules declaring a `test-jar` dependency on
it. A change to a test base class in `camel-core` triggers testing of only those 25 modules instead
of all 518 (measured on Camel main @ `384a00a8`, 2026-08-27). Reproduce the 25: build the
reactor-internal dependency graph from the POMs and count the modules whose `<dependency>` block
for `org.apache.camel:camel-core` also carries `<type>test-jar</type>`:

```bash
git clone --depth 1 --filter=blob:none --sparse https://github.com/apache/camel.git
cd camel && git sparse-checkout set --no-cone '*.xml'
find . -name pom.xml | while read f; do
  awk 'BEGIN{RS="</dependency>"} /<artifactId>camel-core<\/artifactId>/ && /<type>test-jar<\/type>/ {print FILENAME}' "$f"
done | sort -u | wc -l   # -> 25
```

Test-jar dependencies are declared in Maven as:

```xml
<dependency>
    <groupId>org.apache.camel</groupId>
    <artifactId>camel-core</artifactId>
    <type>test-jar</type>
    <scope>test</scope>
</dependency>
```

In `report` mode, each directly affected module includes a `"sourceSet"` field (`"main"` or `"test"`) indicating which source set triggered the change.

## Git Worktree Support

Scalpel works correctly in git worktrees, where `.git` is a file pointing to the main repository rather than a directory.

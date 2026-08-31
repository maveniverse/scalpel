# CI Recipes

This document provides worked CI pipeline examples for common platforms using Scalpel.

All recipes that run Scalpel use `fetch-depth: 0` (a full clone). `actions/checkout` defaults to a shallow clone, and Scalpel needs the merge base between the base branch and HEAD; on a shallow clone it falls back to a full build (the fail-safe path) instead of detecting changes incrementally. The alternative is keeping the shallow clone and passing `-Dscalpel.fetchBaseBranch=true`.

## GitHub Actions

### Basic Incremental Build

```yaml
name: CI
on:
  pull_request:
  push:
    branches: [main]

jobs:
  scalpel:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build with Scalpel
        run: mvn verify
```

On `pull_request` events, Scalpel automatically detects `GITHUB_BASE_REF` as the base branch. On `push` events to the branch itself, pass an explicit base (see the next recipe).

### Matrix Deployment Based on Impacted Modules

```yaml
name: Deploy
on:
  push:
    branches: [main]

jobs:
  detect:
    runs-on: ubuntu-latest
    outputs:
      modules: ${{ steps.scalpel.outputs.modules }}
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Generate Scalpel report
        run: mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=${{ github.event.before }}
      - name: Extract impacted modules
        id: scalpel
        run: |
          modules=$(jq -c '[.affectedModules[].path]' target/scalpel-report.json)
          echo "modules=$modules" >> $GITHUB_OUTPUT
      - name: Show modules
        run: echo "${{ steps.scalpel.outputs.modules }}"

  deploy:
    needs: detect
    runs-on: ubuntu-latest
    strategy:
      matrix:
        module: ${{ fromJSON(needs.detect.outputs.modules) }}
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Deploy module
        run: mvn deploy -pl ${{ matrix.module }}
```

Note the base branch: on a `push` event, `origin/main` resolves to the commit just pushed, so Scalpel would compare HEAD with itself and detect no changes. `${{ github.event.before }}` is the previous commit on the branch and gives the intended diff. The `jq -c '[.affectedModules[].path]'` form produces a JSON array, which is what `fromJSON` in the matrix expects; the plain `-r` form emits newline-separated text and fails to parse.

### Using Impacted Log for Sequential Jobs

```yaml
name: CI
on:
  pull_request:

jobs:
  test:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
        with:
          fetch-depth: 0
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Generate impacted log
        run: mvn validate -Dscalpel.mode=report -Dscalpel.impactedLog=target/scalpel-impacted.log
      - name: Upload impacted log
        uses: actions/upload-artifact@v4
        with:
          name: impacted-log
          path: target/scalpel-impacted.log

  deploy:
    needs: test
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Download impacted log
        uses: actions/download-artifact@v4
        with:
          name: impacted-log
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Deploy impacted modules
        run: |
          while IFS= read -r module; do
            mvn deploy -pl "$module"
          done < impacted-log
```

The checkout runs before `download-artifact` because `actions/checkout` cleans the workspace (`git clean -ffdx`) and would remove a previously downloaded artifact.

## GitLab CI

### Basic Incremental Build

```yaml
# .gitlab-ci.yml
image: maven:3.9-eclipse-temurin-17

variables:
  GIT_DEPTH: 0

build:
  script:
    - mvn verify
  only:
    - merge_requests
```

Scalpel automatically detects `CI_MERGE_REQUEST_TARGET_BRANCH_NAME` as the base branch. `GIT_DEPTH: 0` gives the runner a full clone for the same merge-base reason as above.

### Skip Tests on Unaffected Modules

```yaml
# .gitlab-ci.yml
image: maven:3.9-eclipse-temurin-17

variables:
  GIT_DEPTH: 0

build:
  script:
    - mvn verify -Dscalpel.mode=skip-tests -Dscalpel.baseBranch=$CI_MERGE_REQUEST_TARGET_BRANCH_NAME
  only:
    - merge_requests
```

## Jenkins

### Declarative Pipeline with Scalpel Report

```groovy
pipeline {
    agent any
    stages {
        stage('Checkout') {
            steps {
                checkout scm
            }
        }
        stage('Build') {
            steps {
                sh 'mvn verify -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main'
            }
        }
        stage('Parse Report') {
            steps {
                script {
                    def report = readJSON file: 'target/scalpel-report.json'
                    def affectedModules = report.affectedModules*.path
                    echo "Affected modules: ${affectedModules.join(', ')}"
                    // Use affectedModules for downstream deployment or testing
                }
            }
        }
    }
}
```

### Multibranch Pipeline with Automatic Base Branch

```groovy
pipeline {
    agent any
    stages {
        stage('Build') {
            steps {
                script {
                    // CHANGE_TARGET is set on pull-request builds; fall back to a stable
                    // base for branch builds. Do not fall back to BRANCH_NAME: Scalpel
                    // would compare HEAD with itself and detect no changes.
                    def baseBranch = env.CHANGE_TARGET ?: 'origin/main'
                    sh "mvn verify -Dscalpel.baseBranch=${baseBranch}"
                }
            }
        }
    }
}
```

## Shallow Clone Handling

In CI environments with shallow clones, ensure the base branch is available with complete history:

```bash
git fetch --no-tags origin main
```

Or let Scalpel fetch the base ref itself before change detection:

```bash
mvn verify -Dscalpel.fetchBaseBranch=true
```

## See Also

* [Configuration Reference](CONFIGURATION.md)
* [Report Format](REPORT-FORMAT.md)

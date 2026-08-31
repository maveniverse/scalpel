# CI Recipes

This document provides worked CI pipeline examples for common platforms using Scalpel.

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
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Build with Scalpel
        run: mvn verify
```

Scalpel automatically detects `GITHUB_BASE_REF` as the base branch.

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
      - name: Set up JDK
        uses: actions/setup-java@v4
        with:
          java-version: '17'
          distribution: 'temurin'
      - name: Generate Scalpel report
        run: mvn validate -Dscalpel.mode=report -Dscalpel.baseBranch=origin/main
      - name: Extract impacted modules
        id: scalpel
        run: |
          modules=$(jq -r '.affectedModules[].path' target/scalpel-report.json)
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
      - uses: actions/download-artifact@v4
        with:
          name: impacted-log
      - uses: actions/checkout@v4
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

## GitLab CI

### Basic Incremental Build

```gitlab
# .gitlab-ci.yml
image: maven:3.9-eclipse-temurin-17

variables:
  MAVEN_CLI_OPTS: "-Dscalpel.mode=trim"

build:
  script:
    - mvn verify
  only:
    - merge_requests
```

Scalpel automatically detects `CI_MERGE_REQUEST_TARGET_BRANCH_NAME` as the base branch.

### Skip Tests on Unaffected Modules

```gitlab
# .gitlab-ci.yml
image: maven:3.9-eclipse-temurin-17

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
                    def baseBranch = env.CHANGE_TARGET ?: env.BRANCH_NAME
                    sh "mvn verify -Dscalpel.baseBranch=${baseBranch}"
                }
            }
        }
    }
}
```

## Shallow Clone Handling

In CI environments with shallow clones, ensure the base branch is fetched:

```yaml
- name: Fetch base branch
  run: |
    git fetch --no-tags origin/main
```

Or use Scalpel's built-in fetch:

```bash
mvn verify -Dscalpel.fetchBaseBranch=true
```

## See Also

* [Configuration Reference](CONFIGURATION.md)
* [Report Format](REPORT-FORMAT.md)

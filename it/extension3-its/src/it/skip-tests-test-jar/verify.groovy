/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
File buildLog = new File(basedir, 'build.log')
assert buildLog.exists()
List<String> lines = buildLog.readLines()
String log = buildLog.text

// Scalpel should be activated in skip-tests mode
assert log.contains('Scalpel') : "Expected Scalpel to be activated"
assert log.contains('mode=skip-tests') : "Expected skip-tests mode"

// The underlying reactor build must succeed: module-a's test-compile must not fail looking
// for classes that were never compiled in module-common's (skip-tested) test-jar.
assert log.contains('BUILD SUCCESS') : "Expected BUILD SUCCESS, got:\n$log"

// module-b should be directly affected (source changed)
def directlyAffectedLine = lines.find { it.contains('directly affected') }
assert directlyAffectedLine != null : "Expected 'directly affected' log line"
assert directlyAffectedLine.contains('module-b') : "module-b should be directly affected"

// module-common's skip must be softened: maven.test.skip=true would have suppressed its
// test-compile (and left its test-jar without TestSupport); the fix keeps test-compile alive
// and skips only the surefire/failsafe execution via skipTests=true instead. The per-candidate
// softening detail is logged at debug (not visible here); the aggregate INFO summary is what's
// checked in default (non-debug) build output.
def softenedLine = lines.find { it.contains('test-compile restored') && it.contains('module-common') }
assert softenedLine != null : "Expected the aggregate summary to report module-common's test-compile was restored (softened), got:\n$log"

// Returns the log lines belonging to the plugin execution section starting at sectionStart
// (a "--- goal ... ---" header line), up to (excluding) the next such header. Plugin artifact
// download output can interleave arbitrarily many lines within a section, so bound by the next
// section header rather than a fixed line count.
def sectionText = { int sectionStart ->
    int nextSection = (sectionStart + 1..<lines.size()).find { i -> lines[i].contains('[INFO] --- ') } ?: lines.size() - 1
    lines[sectionStart..nextSection].join('\n')
}

// module-common's surefire execution itself should be skipped (proves skipTests=true took
// effect, as opposed to maven.test.skip which would skip test-compile too)
int commonSurefireIdx = lines.findIndexOf { it.contains('surefire') && it.contains(':test') && it.contains('@ module-common') }
assert commonSurefireIdx >= 0 : "Expected a surefire execution section for module-common, got:\n$log"
def commonSurefireSection = sectionText(commonSurefireIdx)
assert commonSurefireSection.contains('Tests are skipped') : "Expected module-common's surefire execution to be skipped, got:\n$commonSurefireSection"

// module-common's test-jar must have been produced (test-compile was preserved)
assert log.contains('test-jar) @ module-common') : "Expected module-common's test-jar to be built, got:\n$log"

// module-a's test must have actually compiled and run (it is a downstream module, not
// skip-tested, and its test-compile depends on module-common's test-jar being populated)
int aSurefireIdx = lines.findIndexOf { it.contains('surefire') && it.contains(':test') && it.contains('@ module-a') }
assert aSurefireIdx >= 0 : "Expected a surefire execution section for module-a, got:\n$log"
def aSurefireSection = sectionText(aSurefireIdx)
assert aSurefireSection.contains('Tests run: 1, Failures: 0, Errors: 0') : "Expected module-a's test to run and pass, got:\n$aSurefireSection"

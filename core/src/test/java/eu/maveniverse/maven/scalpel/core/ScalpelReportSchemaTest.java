/*
 * Copyright (c) Maveniverse Org.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v2.0
 * which accompanies this distribution, and is available at
 * https://www.eclipse.org/legal/epl-v20.html
 */
package eu.maveniverse.maven.scalpel.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Validates the JSON emitted by {@link ScalpelReport#toJson()} against the checked-in
 * scalpel-report-v2.schema.json, so the schema cannot drift from what the code emits.
 *
 * <p>Validator choice: core ships as part of a Maven extension and has no JSON library on its
 * compile or test classpath (only slf4j, javax.inject, sisu, jgit, junit). Rather than growing
 * the dependency footprint with a JSON parser plus a schema validator (e.g.
 * com.networknt:json-schema-validator and its Jackson transitives) for one test, this class
 * carries a compact JSON reader and a validator for the exact JSON Schema subset the schema
 * uses: {@code $ref} into {@code $defs}, {@code type}, {@code const}, {@code enum},
 * {@code required}, {@code properties}, {@code items}, {@code minimum}, {@code minItems}.
 * The validator reports any keyword it does not implement as an error, so a future schema
 * keyword cannot silently weaken this test.
 */
class ScalpelReportSchemaTest {

    private static Map<String, Object> schema;

    @BeforeAll
    static void loadSchema() throws IOException {
        try (InputStream is = ScalpelReportSchemaTest.class.getResourceAsStream(
                "/eu/maveniverse/maven/scalpel/core/scalpel-report-v2.schema.json")) {
            assertNotNull(is, "scalpel-report-v2.schema.json must be on the classpath");
            String text = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            assertTrue(
                    !text.contains("<<<<<<<") && !text.contains("=======") && !text.contains(">>>>>>>"),
                    "schema file must not contain git merge conflict markers");
            schema = cast(Json.parse(text), "schema root");
        }
    }

    // ---------------------------------------------------------------
    // Schema validation of emitted reports
    // ---------------------------------------------------------------

    @Test
    void representativeReportWithAllOptionalFields_validatesAgainstSchema() {
        Timings timings = new Timings();
        timings.start(Timings.PHASE_DIFF);
        timings.stop(Timings.PHASE_DIFF);
        timings.increment(Timings.OP_GIT_BLOBS_READ, 2);
        timings.increment(Timings.OP_EFFECTIVE_MODELS, 6);
        timings.increment(Timings.OP_DEPENDENCY_RESOLVES, 4);
        timings.increment(Timings.OP_RESOLVE_CACHE_HITS, 1);
        timings.increment(Timings.OP_RESOURCES_VISITED, 12);
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .status("skipped")
                .reason("integration test fixture")
                .fullBuildTriggered(false)
                .changedFiles(List.of("module-a/src/main/java/Foo.java", "gated-module/pom.xml"))
                .changedProperties(List.of("kafka.version"))
                .changedManagedDependencies(List.of("org.apache.kafka:kafka-clients"))
                .changedManagedPlugins(List.of("org.apache.maven.plugins:maven-compiler-plugin"))
                .unmatchedPomPaths(List.of("gated-module/pom.xml"))
                .excludedUpstreamCount(1)
                .timings(timings, 123)
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .sourceSet("main")
                        .evidence(List.of("module-a/src/main/java/Foo.java"))
                        .build())
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example",
                                "module-b",
                                "module-b",
                                List.of(ScalpelReport.REASON_DOWNSTREAM_DEPENDENT))
                        .category(ScalpelReport.CATEGORY_DOWNSTREAM)
                        .testsSkippedReason(ScalpelReport.REASON_EXCLUDED_DOWNSTREAM)
                        .build())
                .addSkippedModule(new ScalpelReport.SkippedModule(
                        "com.example", "module-c", "module-c", ScalpelReport.SKIP_REASON_NOT_AFFECTED))
                .build();

        assertEquals(List.of(), validate(report), "report with every optional field populated must validate");
    }

    @Test
    void minimalReportWithoutOptionalFields_validatesAgainstSchema() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .addAffectedModule(new ScalpelReport.AffectedModule(
                        "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE)))
                .build();

        assertEquals(List.of(), validate(report), "minimal report must validate");
    }

    @Test
    void statusOnlyReport_validatesAgainstSchema() {
        // Mirrors ScalpelLifecycleParticipant.writeStatusReport: the minimal document written
        // when analysis did not complete or was deliberately skipped.
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("(unconfigured)")
                .status("skipped")
                .reason("no changes detected")
                .fullBuildTriggered(true)
                .build();

        assertEquals(List.of(), validate(report), "status-only report must validate");
    }

    @Test
    void schema_rejectsInvalidReports() {
        ScalpelReport report = ScalpelReport.builder()
                .baseBranch("origin/main")
                .fullBuildTriggered(false)
                .excludedUpstreamCount(1)
                .addAffectedModule(ScalpelReport.AffectedModule.moduleBuilder(
                                "com.example", "module-a", "module-a", List.of(ScalpelReport.REASON_SOURCE_CHANGE))
                        .category(ScalpelReport.CATEGORY_DIRECT)
                        .build())
                .addSkippedModule(new ScalpelReport.SkippedModule(
                        "com.example", "module-c", "module-c", ScalpelReport.SKIP_REASON_NOT_AFFECTED))
                .build();

        // Negative controls: prove the validator actually validates, not vacuously passes.
        assertTrue(
                validate(mutate(report, m -> m.remove("affectedModules"))).stream()
                        .anyMatch(e -> e.contains("affectedModules")),
                "missing required field must be rejected");
        assertTrue(
                validate(mutate(report, m -> m.put("fullBuildTriggered", "yes"))).stream()
                        .anyMatch(e -> e.contains("fullBuildTriggered")),
                "wrong type must be rejected");
        assertTrue(
                validate(mutate(report, m -> m.put("status", "pending"))).stream()
                        .anyMatch(e -> e.contains("status")),
                "unknown status enum value must be rejected");
        assertTrue(
                validate(mutate(report, m -> m.put("excludedUpstreamCount", -1L))).stream()
                        .anyMatch(e -> e.contains("excludedUpstreamCount")),
                "excludedUpstreamCount below minimum must be rejected");
        assertTrue(
                validate(mutate(report, m -> {
                            List<Object> modules = cast(m.get("affectedModules"), "affectedModules");
                            Map<String, Object> module = cast(modules.get(0), "affectedModules[0]");
                            module.put("reasons", List.of("BOGUS_REASON"));
                        }))
                        .stream()
                        .anyMatch(e -> e.contains("BOGUS_REASON")),
                "unknown module reason must be rejected");
        assertTrue(
                validate(mutate(report, m -> {
                            List<Object> modules = cast(m.get("skippedModules"), "skippedModules");
                            Map<String, Object> module = cast(modules.get(0), "skippedModules[0]");
                            module.put("reason", "WHATEVER");
                        }))
                        .stream()
                        .anyMatch(e -> e.contains("WHATEVER")),
                "unknown skipped-module reason must be rejected");
        assertTrue(
                validate(mutate(report, m -> m.put("timings", Map.of("totalMillis", -1L, "phases", Map.of())))).stream()
                        .anyMatch(e -> e.contains("totalMillis")),
                "negative totalMillis must be rejected");
        assertTrue(
                validate(mutate(
                                report,
                                m -> m.put("timings", Map.of("totalMillis", 0L, "phases", Map.of("diff", -1L)))))
                        .stream()
                        .anyMatch(e -> e.contains("diff")),
                "negative phase duration must be rejected");
    }

    // ---------------------------------------------------------------
    // Schema/code drift guards
    // ---------------------------------------------------------------

    @Test
    void schemaModuleReasons_matchEmittableConstants() {
        // Every REASON_* constant that can appear in affectedModules.reasons, and nothing else.
        List<String> expected = List.of(
                ScalpelReport.REASON_SOURCE_CHANGE,
                ScalpelReport.REASON_TEST_CHANGE,
                ScalpelReport.REASON_POM_CHANGE,
                ScalpelReport.REASON_TRANSITIVE_DEPENDENCY,
                ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_TEST,
                ScalpelReport.REASON_TRANSITIVE_DEPENDENCY_UNRESOLVED,
                ScalpelReport.REASON_MANAGED_PLUGIN,
                ScalpelReport.REASON_DOWNSTREAM_DEPENDENT,
                ScalpelReport.REASON_DOWNSTREAM_TEST,
                ScalpelReport.REASON_FORCE_BUILD);
        assertEquals(expected, moduleReasonsEnum());
    }

    @Test
    void schemaSkippedReasons_matchEmittableConstants() {
        assertEquals(List.of(ScalpelReport.SKIP_REASON_NOT_AFFECTED), skippedModuleReasonsEnum());
    }

    @Test
    void schema_declaresOptionalTimingAndOperationProperties() {
        // #99: toJson can emit a timings object (totalMillis + per-phase millis) and an
        // operations object (named counters) next to the analysis fields. The validator
        // ignores undeclared instance properties by design, so presence must be asserted
        // against the schema itself or the declaration would drift.
        Map<String, Object> props = cast(schema.get("properties"), "schema.properties");
        assertTrue(
                props.containsKey("timings"),
                "schema must declare the optional 'timings' object emitted with phase timing instrumentation");
        assertTrue(
                props.containsKey("operations"),
                "schema must declare the optional 'operations' object emitted with operation counters");
        Map<String, Object> timings = cast(props.get("timings"), "schema.properties.timings");
        Map<String, Object> timingsProps = cast(timings.get("properties"), "schema.properties.timings.properties");
        assertTrue(timingsProps.containsKey("totalMillis"), "timings must declare totalMillis");
        assertTrue(timingsProps.containsKey("phases"), "timings must declare the phases map");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    private interface Mutation {
        void apply(Map<String, Object> document);
    }

    private static List<String> validate(ScalpelReport report) {
        return new JsonSchema(schema).validate(Json.parse(report.toJson()));
    }

    private static List<String> validate(Map<String, Object> document) {
        return new JsonSchema(schema).validate(document);
    }

    private static Map<String, Object> mutate(ScalpelReport report, Mutation mutation) {
        Map<String, Object> document = cast(Json.parse(report.toJson()), "document");
        mutation.apply(document);
        return document;
    }

    private static List<String> moduleReasonsEnum() {
        Map<String, Object> affectedModule = cast(defs().get("affectedModule"), "$defs.affectedModule");
        Map<String, Object> properties = cast(affectedModule.get("properties"), "affectedModule.properties");
        Map<String, Object> reasons = cast(properties.get("reasons"), "affectedModule.properties.reasons");
        Map<String, Object> items = cast(reasons.get("items"), "affectedModule.properties.reasons.items");
        return cast(items.get("enum"), "affectedModule.properties.reasons.items.enum");
    }

    private static List<String> skippedModuleReasonsEnum() {
        Map<String, Object> skippedModule = cast(defs().get("skippedModule"), "$defs.skippedModule");
        Map<String, Object> properties = cast(skippedModule.get("properties"), "skippedModule.properties");
        Map<String, Object> reason = cast(properties.get("reason"), "skippedModule.properties.reason");
        return cast(reason.get("enum"), "skippedModule.properties.reason.enum");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> defs() {
        return cast(schema.get("$defs"), "$defs");
    }

    @SuppressWarnings("unchecked")
    private static <T> T cast(Object value, String what) {
        if (value == null) {
            throw new IllegalStateException("expected " + what + " but found null");
        }
        return (T) value;
    }

    /**
     * Minimal recursive-descent JSON reader. Produces {@code Map<String,Object>} (insertion
     * ordered), {@code List<Object>}, {@code String}, {@code Long}, {@code Double},
     * {@code Boolean}, and {@code null}. Enough for the report and the schema; not a
     * general-purpose parser.
     */
    static final class Json {
        private final String text;
        private int pos;

        private Json(String text) {
            this.text = text;
        }

        static Object parse(String text) {
            Json parser = new Json(text);
            parser.skipWhitespace();
            Object value = parser.parseValue();
            parser.skipWhitespace();
            if (parser.pos != parser.text.length()) {
                throw parser.error("trailing content");
            }
            return value;
        }

        private Object parseValue() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            char c = text.charAt(pos);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        return parseNumber();
                    }
                    throw error("unexpected character '" + c + "'");
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> result = new LinkedHashMap<>();
            pos++; // '{'
            skipWhitespace();
            if (peek() == '}') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                if (peek() != '"') {
                    throw error("expected object key");
                }
                String key = parseString();
                skipWhitespace();
                if (peek() != ':') {
                    throw error("expected ':'");
                }
                pos++;
                skipWhitespace();
                result.put(key, parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == '}') {
                    pos++;
                    return result;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> result = new ArrayList<>();
            pos++; // '['
            skipWhitespace();
            if (peek() == ']') {
                pos++;
                return result;
            }
            while (true) {
                skipWhitespace();
                result.add(parseValue());
                skipWhitespace();
                char c = peek();
                if (c == ',') {
                    pos++;
                } else if (c == ']') {
                    pos++;
                    return result;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String parseString() {
            StringBuilder sb = new StringBuilder();
            pos++; // opening '"'
            while (true) {
                if (pos >= text.length()) {
                    throw error("unterminated string");
                }
                char c = text.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (pos >= text.length()) {
                        throw error("unterminated escape");
                    }
                    char escaped = text.charAt(pos++);
                    switch (escaped) {
                        case '"':
                            sb.append('"');
                            break;
                        case '\\':
                            sb.append('\\');
                            break;
                        case '/':
                            sb.append('/');
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            if (pos + 4 > text.length()) {
                                throw error("truncated unicode escape");
                            }
                            sb.append((char) Integer.parseInt(text.substring(pos, pos + 4), 16));
                            pos += 4;
                            break;
                        default:
                            throw error("invalid escape '\\" + escaped + "'");
                    }
                } else {
                    sb.append(c);
                }
            }
        }

        private Object parseNumber() {
            int start = pos;
            while (pos < text.length()) {
                char c = text.charAt(pos);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    pos++;
                } else {
                    break;
                }
            }
            String literal = text.substring(start, pos);
            if (literal.indexOf('.') < 0 && literal.indexOf('e') < 0 && literal.indexOf('E') < 0) {
                return Long.parseLong(literal);
            }
            return Double.parseDouble(literal);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, pos)) {
                throw error("expected '" + literal + "'");
            }
            pos += literal.length();
        }

        private char peek() {
            if (pos >= text.length()) {
                throw error("unexpected end of input");
            }
            return text.charAt(pos);
        }

        private void skipWhitespace() {
            while (pos < text.length() && Character.isWhitespace(text.charAt(pos))) {
                pos++;
            }
        }

        private IllegalArgumentException error(String message) {
            int from = Math.max(0, pos - 20);
            int to = Math.min(text.length(), pos + 20);
            return new IllegalArgumentException(
                    message + " at offset " + pos + " (near \"" + text.substring(from, to) + "\")");
        }
    }

    /**
     * Validates an instance against the JSON Schema subset used by the checked-in schema.
     * Unknown structural keywords are reported as errors on purpose (see class javadoc).
     */
    static final class JsonSchema {
        private static final List<String> SUPPORTED_KEYWORDS = List.of(
                "$schema",
                "$id",
                "title",
                "description",
                "$defs",
                "$ref",
                "type",
                "const",
                "enum",
                "required",
                "properties",
                "additionalProperties",
                "items",
                "minimum",
                "minItems");

        private final Map<String, Object> root;

        JsonSchema(Map<String, Object> root) {
            this.root = root;
        }

        List<String> validate(Object instance) {
            List<String> errors = new ArrayList<>();
            validateSchema(root, instance, "$", errors);
            return errors;
        }

        @SuppressWarnings("unchecked")
        private void validateSchema(Map<String, Object> schema, Object instance, String path, List<String> errors) {
            for (String keyword : schema.keySet()) {
                if (!SUPPORTED_KEYWORDS.contains(keyword)) {
                    errors.add(path + ": schema uses unsupported keyword '" + keyword
                            + "' (extend the test validator or the schema deliberately)");
                }
            }
            if (schema.containsKey("$ref")) {
                String ref = cast(schema.get("$ref"), path + ".$ref");
                if (!ref.startsWith("#/$defs/")) {
                    errors.add(path + ": unsupported $ref '" + ref + "' (only '#/$defs/<name>' is supported)");
                    return;
                }
                Map<String, Object> defs = cast(root.get("$defs"), "$defs");
                Map<String, Object> target = cast(defs.get(ref.substring("#/$defs/".length())), ref);
                validateSchema(target, instance, path, errors);
                return;
            }
            checkType(schema, instance, path, errors);
            if (schema.containsKey("const")) {
                Object expected = schema.get("const");
                if (!java.util.Objects.equals(expected, instance)) {
                    errors.add(path + ": expected const " + expected + " but found " + display(instance));
                }
            }
            if (schema.containsKey("enum")) {
                List<Object> allowed = cast(schema.get("enum"), path + ".enum");
                if (!allowed.contains(instance)) {
                    errors.add(path + ": " + display(instance) + " is not one of " + allowed);
                }
            }
            if (instance instanceof Map) {
                Map<String, Object> object = cast(instance, path);
                List<String> required = cast(schema.getOrDefault("required", List.of()), path + ".required");
                for (String name : required) {
                    if (!object.containsKey(name)) {
                        errors.add(path + ": missing required property '" + name + "'");
                    }
                }
                Map<String, Object> properties =
                        cast(schema.getOrDefault("properties", Map.of()), path + ".properties");
                for (Map.Entry<String, Object> property : properties.entrySet()) {
                    if (object.containsKey(property.getKey())) {
                        validateSchema(
                                cast(property.getValue(), path + "." + property.getKey()),
                                object.get(property.getKey()),
                                path + "." + property.getKey(),
                                errors);
                    }
                }
                if (schema.containsKey("additionalProperties")) {
                    Map<String, Object> addlSchema =
                            cast(schema.get("additionalProperties"), path + ".additionalProperties");
                    for (Map.Entry<String, Object> entry : object.entrySet()) {
                        if (!properties.containsKey(entry.getKey())) {
                            validateSchema(addlSchema, entry.getValue(), path + "." + entry.getKey(), errors);
                        }
                    }
                }
            }
            if (instance instanceof List) {
                List<Object> array = cast(instance, path);
                Number minItems = (Number) schema.get("minItems");
                if (minItems != null && array.size() < minItems.intValue()) {
                    errors.add(path + ": expected at least " + minItems + " items but found " + array.size());
                }
                if (schema.containsKey("items")) {
                    Map<String, Object> items = cast(schema.get("items"), path + ".items");
                    for (int i = 0; i < array.size(); i++) {
                        validateSchema(items, array.get(i), path + "[" + i + "]", errors);
                    }
                }
            }
            if (instance instanceof Number && schema.containsKey("minimum")) {
                double minimum = ((Number) schema.get("minimum")).doubleValue();
                if (((Number) instance).doubleValue() < minimum) {
                    errors.add(path + ": " + instance + " is below minimum " + minimum);
                }
            }
        }

        private void checkType(Map<String, Object> schema, Object instance, String path, List<String> errors) {
            Object declared = schema.get("type");
            if (declared == null) {
                return;
            }
            List<String> types = declared instanceof List ? cast(declared, path + ".type") : List.of((String) declared);
            for (String type : types) {
                if (matchesType(type, instance)) {
                    return;
                }
            }
            errors.add(path + ": " + display(instance) + " does not match type " + types);
        }

        private boolean matchesType(String type, Object instance) {
            switch (type) {
                case "object":
                    return instance instanceof Map;
                case "array":
                    return instance instanceof List;
                case "string":
                    return instance instanceof String;
                case "boolean":
                    return instance instanceof Boolean;
                case "integer":
                    return instance instanceof Long
                            || instance instanceof Double d && d == Math.floor(d) && !d.isInfinite();
                case "number":
                    return instance instanceof Number;
                case "null":
                    return instance == null;
                default:
                    throw new IllegalStateException("unknown JSON type '" + type + "'");
            }
        }

        private static String display(Object instance) {
            return instance == null
                    ? "null"
                    : instance instanceof String ? "\"" + instance + "\"" : instance.toString();
        }

        @SuppressWarnings("unchecked")
        private static <T> T cast(Object value, String what) {
            if (value == null) {
                throw new IllegalStateException("expected " + what + " but found null");
            }
            return (T) value;
        }
    }
}

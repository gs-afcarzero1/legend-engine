# Exact JSON numeric input and safe Decimal output

The user-supplied plan requires separate input and output commits, executable EMIT coverage and cross-store validation. Repository guidance prohibits a full reactor build and `-am` for this work.

## Purpose

JSON scientific notation must retain decimal precision regardless of other properties in the model. Integer conversion must reject fractions and overflow, Float conversion must reject overflow and nonzero underflow, and Decimal output must fall back to a numeric exponent token beyond Jackson's plain scale limit.

## Progress

- [x] Inspected repository guidance, JSON runtime reader, generated legacy reader, writers and EMIT runners.
- [x] Inventoried 118 source EMIT descriptors at 07cc41529fd; all tracked in this checkout. The referenced uncommitted corpus has not been located.
- [x] Attempted the requested baseline. No tests executed: current EMIT runner dependencies are missing.
- [x] Implemented shared input support and connected both reader paths.
- [x] Input helper (6 tests), runtime reader (5 tests), generated legacy reader (2 tests), stored-plan executor (7 tests), and assertion evaluator (25 tests) pass.
- [x] Added and executed input EMIT coverage for ModelStore data, JSON bindings/internalization and legacy `JsonModelConnection` readers.
- [x] Committed input correctness as `dc9409a29e1`.
- [x] Implemented output support. Writer helper (3 tests), graph serialization (3 tests), and externalization (2 tests) pass.
- [x] Added and executed output EMIT coverage for graph serialization, externalization, service envelopes and mapping-consumed output.
- [x] Executed the complete available EMIT corpus: 1,170 passing test invocations, no failures and no skips.
- [x] Executed H2 and DuckDB cross-store services and checked Snowflake-specific plans and SQL.
- [ ] Execute the Snowflake services against a live Snowflake connection. No Snowflake test environment is configured in this checkout.

## Surprises & Discoveries

Current source version is 4.145.1-SNAPSHOT, but the original core EMIT target artifacts identified themselves as 4.139.3-SNAPSHOT. Current `legend-engine-emit-junit` and `legend-engine-test-runner-mapping` dependencies were absent. The baseline Maven command failed at dependency resolution and skipped subsequent modules, so the old target reports could not establish a full matching-revision baseline. The later full build failed at dependency analysis in the changed dependencies module; declaring jackson-databind fixed that failure and the module now installs successfully.

Generated Java compilation also needs JsonNumericSupport in ExecutionPlanDependenciesFilter. The generated-reader test found this; after registration, both new tests and all seven stored-plan executor tests passed.

Original-source isolated Java baseline: 7 runtime JSON tests executed, 5 failed; 3 graph writer tests executed, 2 failed. Logs are in `java-baseline/`. The same tests pass on changed classes. These failures establish the targeted regressions even though the full original-revision EMIT baseline could not run.

The requested uncommitted corpus was not present. The checkout contained 118 descriptors at `07cc41529fd`, all tracked. Other local worktrees and the cached Gitea remote contained fewer descriptors; fetching the Gitea remote failed because its SSH key was unavailable. Eight new descriptors bring the source inventory to 126.

## Decision Log

2026-09-12: Preserve the constructor boolean for binary/generated-code compatibility, but parse all fractional tokens exactly independent of model properties. Wrap the outermost parser, including JSON pointer filters, and expose BIG_DECIMAL before Jackson's tree reader requests a double. Existing reader type checks and IllegalArgumentException-to-defect handling remain in place.

2026-09-12: Record unavailable integration validation explicitly rather than substitute the older installed engine or change baseline expectations. Only touched modules are rebuilt, in dependency order. After the interrupted full build, missing prerequisites for EMIT were explicitly selected for their first current-revision build; already installed prerequisites were reused.

2026-09-13: Retain the config EMIT module's extension collection dependency. Direct dependencies used by the new cross-store test are declared alongside it; a temporary broad replacement used to recover the incomplete local Maven repository is not part of the implementation.

## Context and Orientation

`legend-engine-executionPlan-dependencies` supplies Java classes available to generated plans. `legend-engine-xt-json-runtime` implements binding/internalization readers and JSON externalization. `legend-engine-xt-javaPlatformBinding-pure` generates legacy JsonModelConnection Java readers in graphFetchJson.pure, using numeric getters in jacksonSupport.pure. ExecutionNodeSerializerHelper in `legend-engine-executionPlan-execution` writes graph-fetch output and token buffers.

## Plan of Work

Input changes belong in JsonNumericSupport, JsonDataReader and the two Pure generator files. Focused tests must inspect exact values and Java types, malformed tokens, checked defects and filtered paths. Add executable EMIT mapping/service fixtures alongside existing resources; leave existing expectations intact. Commit these changes first. Output changes then use one shared writer policy from both serializers, preserving ordinary formatting and BigDecimal token-buffer values. Add separate output coverage before committing.

## Validation Results

The complete available EMIT run produced:

- EMIT framework and JUnit resource tests: 41 passed.
- Core grammar and M2M: 211 passed.
- Relational and relation: 698 passed.
- Service: 31 passed.
- Config and cross-feature: 143 passed.
- Persistence: 46 passed.
- Total: 1,170 passed, 0 failed, 0 skipped.

The 128 new invocations comprise 65 M2M assertions, 60 service assertions and 3 cross-store Java tests. H2 and DuckDB each executed graph, externalization, tabular, arithmetic/comparison/cast and aggregation services. Independent streaming-parser assertions checked large integers and exact decimals. Snowflake validation compiled the same five services with an actual Snowflake connection type and asserted Snowflake session SQL, casts, Decimal parameter metadata and aggregation SQL; it did not execute the plans.

Focused checks also passed: 15 shared dependency tests, 3 execution serializer tests, 7 JSON runtime tests, 25 assertion evaluator tests, 9 in-memory plan tests and the regenerated server-plan integration test. The touched Pure module completed a required clean install with dependency analysis and Checkstyle. All reported suites had zero skips.

## Idempotence and Recovery

Keep all existing fixture files unchanged. baseline-inventory.json records descriptor paths and SHA-256 hashes. Logs are retained under numeric-validation; no credentials should be recorded. Repeating targeted builds/tests is safe; do not mistake stale Surefire reports for current results.

## Outcomes & Retrospective

Input and output correctness are implemented and the complete available corpus passes without unexplained regressions. A matching-revision pre-change EMIT baseline could not be established because required artifacts were missing before implementation. Live Snowflake execution remains outstanding because no Snowflake environment or credentials are configured.

Revision note: Created this record after discovering the missing baseline build, to preserve the user's acceptance requirements and avoid claiming old artifacts as validation.

Revision note: Updated Java verification, generated-code registration, original-source regression evidence, complete EMIT results and cross-store status. Live Snowflake is outstanding: neither `PCT_EXTERNAL_RESOURCES_PROPERTIES` nor AWS integration credentials is configured.

# Schema Validation Design

**Issue:** #7 (schema validation), #11 (MockWorkerExecutor hardening)
**Date:** 2026-07-06
**Status:** Approved

## Context

`Capability` declares `inputProjection` and `outputProjection` as non-null `String` fields,
but nothing validates inputs or outputs against them. These fields are metadata-only
today. All existing tests use `"{}"` as placeholder schemas.

Issue #11 is a minor hardening of `MockWorkerExecutor` — a `Sync` cast guard and a
missing null-capability test — bundled on the same branch.

## Decision: JSON Schema with networknt

Schema format is JSON Schema (Draft 2020-12). The validation library is
`com.networknt:json-schema-validator` — Jackson-based (already in Quarkus stack),
actively maintained, fastest JVM implementation, supports latest drafts.

Alternatives considered:
- **everit-org/json-schema** — deprecated in favour of json-sKema, no Draft 2020-12 support, uses org.json (not Jackson)
- **Manual validation** — fragile, limited, not worth maintaining
- **json-sKema** — successor to everit, less established ecosystem

## Architecture

### SchemaValidator (new, runtime module)

`@ApplicationScoped` CDI bean with three public methods:

```java
public void ensureSchemaParsed(String schema)
public Optional<String> validateInput(Capability capability, Map<String, Object> input)
public Optional<String> validateOutput(Capability capability, Map<String, Object> output)
```

`validateInput`/`validateOutput` return `Optional.empty()` on valid,
`Optional.of(errorMessage)` on invalid. The validator does not know about
`WorkerResult` or OTel — the caller decides what to do with the result.

`ensureSchemaParsed` parses and caches a schema string, throwing
`IllegalArgumentException` if malformed. Called by the executor in the
programming-error guard section (before the span), so malformed schemas
propagate directly as unchecked exceptions — never caught by the executor's
exception-to-outcome conversion. Schema is defined at build time, not
runtime — a parse failure is a programming error, same category as null
capability or capability-not-in-worker.

Internal details:
- `ConcurrentHashMap<String, JsonSchema>` caches parsed schemas keyed on the raw schema string
- `"{}"` checked first — `ensureSchemaParsed` returns immediately,
  `validateInput`/`validateOutput` return `Optional.empty()` (performance
  shortcut; an empty JSON Schema validates everything per the spec, so this is
  an optimisation, not a semantic change)
- Uses a private `ObjectMapper` instance to convert `Map<String, Object>` to
  `JsonNode` (simple `valueToTree` — no custom config needed, no CDI dependency)
- Error message concatenates all validation errors, one per line

### DefaultWorkerExecutor integration

Gains `SchemaValidator` as a constructor dependency (CDI injection alongside
`PolicyEnforcer`). Three call sites in `execute()`:

**Schema parse guard** — after Sync-type check, before span creation (alongside
other programming-error guards):
- `schemaValidator.ensureSchemaParsed(capability.inputSchema())`
- `schemaValidator.ensureSchemaParsed(capability.outputSchema())`
- Malformed schema throws `IllegalArgumentException`, propagating directly —
  same category as null capability or capability-not-in-worker.

**Input validation** — inside the span's try-catch, before
`policyEnforcer.execute()`:
- Invalid input: span event `worker.input.invalid` with error detail, set
  `worker.outcome` span attribute to `"Failed"`, return
  `WorkerResult.failed(validationError)`. Function never invoked.

**Output validation** — after `policyEnforcer.execute()` returns, before returning
the result:
- Only runs when outcome is `Success` — checked explicitly via
  `result.outcome() instanceof WorkerOutcome.Success`.
- Invalid output: span event `worker.output.invalid` with error detail, log WARN
  with worker name, capability name, and validation errors. Return the result
  unchanged.
- `Declined` outcomes are excluded: partial output from a declined worker
  represents incomplete work, not a contract violation against the output schema.
- `Failed`/`Expired` outcomes are excluded: these may carry non-empty partial
  output (via the `partialOutput` factory overloads from spec #2), but this data
  is diagnostic/recovery information, not the capability's declared output
  contract. The executor's own exception-to-outcome conversion produces
  `Map.of()`, but worker-returned failures are not constrained to empty output.

No changes to the `WorkerExecutor` interface — validation is an implementation
detail of `DefaultWorkerExecutor`.

### MockWorkerExecutor

Does NOT get schema validation. It is a test double that bypasses policy
enforcement — schema validation should also be bypassed. Tests that need to verify
schema behaviour use `DefaultWorkerExecutor` directly.

### Input/output validation asymmetry

| Direction | Invalid result | Rationale |
|-----------|---------------|-----------|
| Input | `WorkerResult.failed()` — function never runs | Prevents wasting compute on garbage input |
| Output | Log WARN, return result unchanged | Worker did its job; schema mismatch is observability, not enforcement |

### Cross-repo naming: inputSchema/outputSchema semantics

The engine uses the term `outputProjection` (and `inputProjection`) in a different sense:
the engine's `WorkerExecutor.execute()` takes `String outputSchema` as a JQ
projection expression, not a JSON Schema validation document. The engine's
`Binding.effectiveInputSchema()` and `WorkerScheduleEvent.effectiveInputSchema()`
fall back to `capability.inputSchema()` (the worker-api field) and feed the result
into JQ evaluation.

Today this is harmless because all capabilities use `"{}"`, which is both valid JQ
(creates an empty object) and valid JSON Schema (validates everything). Once
capabilities carry non-trivial JSON Schemas, the engine's JQ fallback will produce
wrong data (interpreting a JSON Schema document as a JQ literal).

The worker-api naming is correct — `inputProjection`/`outputProjection` for JSON Schema
validation documents is the natural name. The engine's use of "schema" for JQ
projection expressions is the terminological anomaly. Resolution:

- The engine's `effectiveInputSchema()` fallback must stop treating
  `capability.inputSchema()` as a JQ expression. The binding should always specify
  its own JQ projection explicitly.
- The engine's `WorkerExecutor.execute()` parameter should be renamed from
  `outputProjection` to `outputProjection` (or similar) to distinguish JQ projection
  from JSON Schema validation.
- Tracked as `casehubio/engine#677`, prerequisite for convergence
  (`casehub-worker#10`).

## Issue #11: MockWorkerExecutor hardening

Two changes, independent of schema validation:

1. **Sync guard:** Add `instanceof Sync` check before the cast at line 34. Throw
   `UnsupportedOperationException` matching `DefaultWorkerExecutor`'s message.
2. **Null-capability test:** Add `execute_nullCapability_throwsNPE` to
   `MockWorkerExecutorTest` — one-liner `assertThatThrownBy`, mirrors the existing
   test in `WorkerExecutorTest`.

## Dependencies

New dependency in `runtime/pom.xml` only:

```xml
<dependency>
    <groupId>com.networknt</groupId>
    <artifactId>json-schema-validator</artifactId>
</dependency>
```

Version managed in `casehub-parent` BOM. If not present, pin locally until the
parent is updated.

No changes to `api` module (stays dependency-free). No networknt dependency in
`testing` module (MockWorkerExecutor doesn't validate).

## Testing

### SchemaValidatorTest (unit, new)

- Valid input against schema returns empty
- Invalid input (missing required field, wrong type) returns error message
- Empty schema `"{}"` skips validation, returns empty
- Schema caching (same string parsed once)
- `ensureSchemaParsed` with valid schema: no exception, schema cached
- `ensureSchemaParsed` with malformed schema: throws `IllegalArgumentException`

### WorkerExecutorTest (integration, additions)

- Valid input + valid output: success (happy path unchanged)
- Invalid input: `Failed` result with validation message, function never called
- Invalid input: `worker.outcome` span attribute set to `"Failed"`
- Invalid output: success result returned, warning logged
- Declined with partial output: no output validation, no warning logged
- Failed with partial output (factory overload): no output validation, no warning
- Empty schemas `"{}"`: no validation, function runs normally (backward compatible)
- Malformed schema string: `IllegalArgumentException` propagates (not converted to `Failed`)

### MockWorkerExecutorTest (additions for #11)

- `execute_nullCapability_throwsNPE`
- `execute_nonSyncFunction_throwsUnsupported`

### TestWorkerBuilder (addition)

- `syncWithCapability` overload accepting input/output schema strings

## Files Changed

| File | Change |
|------|--------|
| `runtime/pom.xml` | Add networknt dependency |
| `runtime/.../SchemaValidator.java` | New: validation logic + schema cache |
| `runtime/.../DefaultWorkerExecutor.java` | Inject SchemaValidator, add input/output validation calls |
| `runtime/.../SchemaValidatorTest.java` | New: unit tests for validator |
| `runtime/.../WorkerExecutorTest.java` | Add validation integration tests |
| `testing/.../MockWorkerExecutor.java` | Add Sync guard (#11) |
| `testing/.../MockWorkerExecutorTest.java` | Add null-capability + non-Sync tests (#11) |
| `testing/.../TestWorkerBuilder.java` | Add schema-aware overload |

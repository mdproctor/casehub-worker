[![Build](https://github.com/casehubio/casehub-worker/actions/workflows/publish.yml/badge.svg?branch=main)](https://github.com/casehubio/casehub-worker/actions/workflows/publish.yml) [![Open PRs](https://img.shields.io/github/issues-pr/casehubio/casehub-worker)](https://github.com/casehubio/casehub-worker/pulls)

# casehub-worker

Foundation-tier automated task primitives for the CaseHub platform. Peer to `casehub-work` (human tasks) — workers handle machine-executed functions with retry policy, timeout enforcement, and OpenTelemetry tracing.

## Modules

| Module | Artifact | Purpose |
|--------|----------|---------|
| `api` | `casehub-worker-api` | `Worker`, `WorkerFunction`, `Capability`, `WorkerResult`, `WorkerOutcome` |
| `runtime` | `casehub-worker` | `WorkerExecutor` with `PolicyEnforcer` + OTel tracing |
| `testing` | `casehub-worker-testing` | `MockWorkerExecutor` + `TestWorkerBuilder` for CDI-based test substitution |

## Build

```bash
mvn clean install
```

Requires Java 21+, Maven 3.9+. Artifacts publish to [GitHub Packages](https://github.com/orgs/casehubio/packages).

## Documentation

- [Design](docs/DESIGN.md) — architecture and module structure
- [ARC42STORIES](ARC42STORIES.MD) — structured architecture documentation
- [ADRs](docs/adr/) — architecture decision records

## Status

Foundation module — API stable, runtime operational, testing harness complete.

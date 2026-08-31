# casehub-worker

## Project Type

type: java

## Repository Role

Foundation-tier automated task primitives — Worker, WorkerFunction, Capability, execution policy. Extracted from `casehub-engine-api` so Workers are a shareable foundation primitive.

**Tier:** Foundation (consumed by engine, desiredstate, and downstream repos)

## Documentation

This repo owns its own documentation, synced to parent via subtree:
- `docs/guides/consumer-guide.md` — for consumers: Worker API, WorkerFunction variants, Capability, WorkerResult
- `docs/guides/contributor-guide.md` — for contributors: DefaultWorkerExecutor internals, CDI wiring

Update the relevant guide in the same session when implementation changes SPIs, records, or the executor. Do not defer — drift compounds. Parent (`casehubio/parent`) aggregates these at `docs/repos/casehub-worker/` for RAG retrieval.

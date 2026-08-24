# io.casehub.worker.api.Worker

**Package:** `io.casehub.worker.api`

**Kind:** `record`

## Fields

### `capabilities` (`java.util.Set<java.lang.String>`)

### `description` (`java.lang.String`)

### `executionPolicy` (`ExecutionPolicy`)

### `function` (`io.casehub.worker.api.WorkerFunction<?,?>`)

### `name` (`java.lang.String`)

## Record Components

### `capabilities` (`java.util.Set<java.lang.String>`)

### `description` (`java.lang.String`)

### `executionPolicy` (`ExecutionPolicy`)

### `function` (`io.casehub.worker.api.WorkerFunction<?,?>`)

### `name` (`java.lang.String`)

## Constructors

### `public Worker(java.lang.String name, java.util.Set<java.lang.String> capabilityNames, io.casehub.worker.api.WorkerFunction<?,?> function, ExecutionPolicy executionPolicy, java.lang.String description)`

#### Parameters

- `name` (`java.lang.String`)
- `capabilities` (`java.util.Set<java.lang.String>`)
- `function` (`io.casehub.worker.api.WorkerFunction<?,?>`)
- `executionPolicy` (`ExecutionPolicy`)
- `description` (`java.lang.String`)

## Methods

### `public static io.casehub.worker.api.Worker.Builder builder()`

### `public java.util.Set<java.lang.String> capabilityNames()`

### `public java.lang.String description()`

### `public final boolean equals(java.lang.Object o)`

#### Parameters

- `o` (`java.lang.Object`)

### `public ExecutionPolicy executionPolicy()`

### `public io.casehub.worker.api.WorkerFunction<?,?> function()`

### `public final int hashCode()`

### `public java.lang.String name()`

### `public final java.lang.String toString()`

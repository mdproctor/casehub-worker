# io.casehub.worker.api.Worker.Builder

**Package:** `io.casehub.worker.api`

**Kind:** `class`

## Fields

### `capabilities` (`java.util.Set<java.lang.String>`)

### `description` (`java.lang.String`)

### `executionPolicy` (`ExecutionPolicy`)

### `function` (`io.casehub.worker.api.WorkerFunction<?,?>`)

### `name` (`java.lang.String`)

## Constructors

### `public Builder()`

## Methods

### `public io.casehub.worker.api.Worker build()`

### `public io.casehub.worker.api.Worker.Builder capabilityName(java.lang.String name)`

#### Parameters

- `name` (`java.lang.String`)

### `public io.casehub.worker.api.Worker.Builder capabilityNames(java.lang.String[] names)`

#### Parameters

- `names` (`java.lang.String[]`)

### `public io.casehub.worker.api.Worker.Builder capabilityNames(java.util.Collection<java.lang.String> names)`

#### Parameters

- `names` (`java.util.Collection<java.lang.String>`)

### `public io.casehub.worker.api.Worker.Builder description(java.lang.String d)`

#### Parameters

- `d` (`java.lang.String`)

### `public io.casehub.worker.api.Worker.Builder executionPolicy(ExecutionPolicy p)`

#### Parameters

- `p` (`ExecutionPolicy`)

### `public final io.casehub.worker.api.TypedFunctionBuilder<T> fn(T[] typeToken)`

#### Parameters

- `typeToken` (`T[]`)

### `public io.casehub.worker.api.Worker.Builder function(io.casehub.worker.api.WorkerFunction<?,?> f)`

#### Parameters

- `f` (`io.casehub.worker.api.WorkerFunction<?,?>`)

### `public io.casehub.worker.api.Worker.Builder function(java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,io.casehub.worker.api.WorkerResult<java.util.Map<java.lang.String,java.lang.Object>>> fn)`

#### Parameters

- `fn` (`java.util.function.Function<java.util.Map<java.lang.String,java.lang.Object>,io.casehub.worker.api.WorkerResult<java.util.Map<java.lang.String,java.lang.Object>>>`)

### `public io.casehub.worker.api.Worker.Builder name(java.lang.String n)`

#### Parameters

- `n` (`java.lang.String`)

### `public io.casehub.worker.api.Worker.Builder noFunction()`

### `void setFunction(io.casehub.worker.api.WorkerFunction<?,?> f)`

#### Parameters

- `f` (`io.casehub.worker.api.WorkerFunction<?,?>`)

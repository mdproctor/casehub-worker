package io.casehub.worker.runtime;

import io.casehub.worker.api.Capability;
import io.casehub.worker.api.Worker;
import io.casehub.worker.api.WorkerResult;

import java.util.Map;

public interface WorkerExecutor {
    /**
     * Executes the given worker for the specified capability with policy enforcement.
     *
     * <p>Three exception categories:
     * <ul>
     *   <li><b>Worker-level conditions</b> (function exceptions, retry exhaustion,
     *       timeout) — returned as {@link WorkerResult} outcomes. Never propagate.</li>
     *   <li><b>Programming errors</b> (null capability, capability not in worker,
     *       non-Sync function) — propagate as exceptions ({@code NullPointerException},
     *       {@code IllegalArgumentException}, {@code UnsupportedOperationException}).
     *       These are call-site bugs, not execution outcomes.</li>
     *   <li><b>Infrastructure signals</b> (thread interrupt, JVM errors) — propagate
     *       as exceptions.</li>
     * </ul>
     */
    WorkerResult execute(Worker worker, Capability capability, Map<String, Object> input);
}

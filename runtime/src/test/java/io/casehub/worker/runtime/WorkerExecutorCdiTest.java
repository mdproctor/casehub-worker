package io.casehub.worker.runtime;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@QuarkusTest
class WorkerExecutorCdiTest {

    @Inject
    WorkerExecutor executor;

    @Test
    void injectedExecutor_resolvesToDefaultImpl() {
        assertThat(executor).isInstanceOf(DefaultWorkerExecutor.class);
    }
}

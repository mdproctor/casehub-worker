package io.casehub.worker.api;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PlannedActionTest {

    @Test
    void construction_allFields() {
        PlannedAction pa = new PlannedAction("File SAR", "sar.file", Map.of("accountId", "ACC-123"));
        assertThat(pa.description()).isEqualTo("File SAR");
        assertThat(pa.actionType()).isEqualTo("sar.file");
        assertThat(pa.parameters()).containsEntry("accountId", "ACC-123");
    }

    @Test
    void construction_nullParameters_defaultsToEmptyMap() {
        PlannedAction pa = new PlannedAction("File SAR", "sar.file", null);
        assertThat(pa.parameters()).isEmpty();
    }

    @Test
    void construction_nullDescription_rejected() {
        assertThatThrownBy(() -> new PlannedAction(null, "sar.file", Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void construction_nullActionType_rejected() {
        assertThatThrownBy(() -> new PlannedAction("File SAR", null, Map.of()))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void of_twoArg_defaultsParametersToEmpty() {
        PlannedAction pa = PlannedAction.of("Approve", "approval.grant");
        assertThat(pa.description()).isEqualTo("Approve");
        assertThat(pa.actionType()).isEqualTo("approval.grant");
        assertThat(pa.parameters()).isEmpty();
    }

    @Test
    void of_threeArg_passesAllFields() {
        Map<String, Object> params = Map.of("amount", 100);
        PlannedAction pa = PlannedAction.of("Transfer", "spend.transfer", params);
        assertThat(pa.description()).isEqualTo("Transfer");
        assertThat(pa.actionType()).isEqualTo("spend.transfer");
        assertThat(pa.parameters()).containsEntry("amount", 100);
    }
}

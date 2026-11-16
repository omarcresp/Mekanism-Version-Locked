package mekanism.api.heat;

import com.mojang.serialization.JsonOps;
import mekanism.api.heat.IHeatCapacitor.CapacitorState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapacitorStateTest {

    @Test
    void roundTripPreservesHeatAndCapacity() {
        CapacitorState state = new CapacitorState(250, 100);
        var encoded = CapacitorState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        Assertions.assertEquals(state, CapacitorState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void emptyCapacitorHasValidState() {
        Assertions.assertDoesNotThrow(() -> new CapacitorState(0, 100));
    }

    @Test
    void rejectsInvalidCapacityIndependentlyOfHeat() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new CapacitorState(100, 0));
    }
}

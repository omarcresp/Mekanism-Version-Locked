package mekanism.api.heat;

import com.mojang.serialization.JsonOps;
import mekanism.api.heat.IHeatCapacitor.CapacitorState;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CapacitorStateTest {

    @Test
    void savedHeatAndCapacityRemainIndependent() {
        var state = new CapacitorState(80_000_000, 98_000);
        var encoded = CapacitorState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        Assertions.assertEquals(state, CapacitorState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void emptyCapacitorCanBeSaved() {
        var state = new CapacitorState(0, 98_000);
        var encoded = CapacitorState.CODEC.encodeStart(JsonOps.INSTANCE, state).getOrThrow();
        Assertions.assertEquals(state, CapacitorState.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow());
    }

    @Test
    void invalidCapacityIsRejectedIndependentlyOfHeat() {
        Assertions.assertThrows(IllegalArgumentException.class, () -> new CapacitorState(100, 0));
    }
}

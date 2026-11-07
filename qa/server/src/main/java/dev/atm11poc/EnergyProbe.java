package dev.atm11poc;

import com.google.gson.JsonParser;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiConsumer;
import mekanism.api.Upgrade;
import mekanism.common.block.attribute.AttributeEnergy;
import mekanism.common.config.MekanismConfig;
import mekanism.common.registries.MekanismBlocks;
import mekanism.common.tile.base.TileEntityMekanism;
import mekanism.common.util.EnumUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerLevel;

/** Compares live capacities with the pinned, converted ATM10 balance values. */
final class EnergyProbe {
    private static final BlockPos START = new BlockPos(8, 80, 48);
    private static final BlockPos FACTORY = new BlockPos(8, 80, 80);
    private static final Map<String, String> BLOCK_NAMES = Map.of(
          "pressurizedReactionBase", "pressurized_reaction_chamber",
          "nutritionalLiquifier", "nutritional_liquifier");
    private final BiConsumer<Boolean, String> check;

    EnergyProbe(BiConsumer<Boolean, String> check) {
        this.check = check;
    }

    void prepare(ServerLevel level, boolean reopen) throws Exception {
        check.accept(MekanismConfig.general.maxUpgradeMultiplier.get() == 16, "ATM10 machine upgrade multiplier is 16");
        if (reopen) {
            verifySaved(level, START, 128000);
            verifySaved(level, FACTORY, 192000);
            return;
        }
        try (var reader = new InputStreamReader(Objects.requireNonNull(EnergyProbe.class.getResourceAsStream("/atm10-8.5-balance.json")), StandardCharsets.UTF_8)) {
            var configs = JsonParser.parseReader(reader).getAsJsonObject().getAsJsonObject("configs");
            var usage = configs.getAsJsonObject("machine-usage.toml");
            int index = 0;
            for (var entry : configs.getAsJsonObject("machine-storage.toml").entrySet()) {
                String key = entry.getKey();
                String id = BLOCK_NAMES.getOrDefault(key, key.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(java.util.Locale.ROOT));
                var pos = START.offset((index % 6) * 2, 0, (index / 6) * 2);
                var tile = place(level, pos, "mekanism:" + id);
                check.accept(tile.getEnergyContainer().getCapacityAsLong() == entry.getValue().getAsLong(), "ATM10 base energy capacity " + id);
                if (usage.has(key) && tile.getEnergyContainer() instanceof mekanism.common.capabilities.energy.MachineEnergyContainer<?> container) {
                    check.accept(container.getEnergyPerTick() == usage.get(key).getAsInt(), "ATM10 base energy usage " + id);
                }
                index++;
            }
        }
        int index = 0;
        for (var tier : EnumUtils.FACTORY_TIERS) {
            for (var type : EnumUtils.FACTORY_TYPES) {
                var pos = FACTORY.offset((index % 9) * 2, 0, (index / 9) * 2);
                var tile = place(level, pos, BuiltInRegistries.BLOCK.getKey(MekanismBlocks.getFactory(tier, type).value()).toString());
                var base = type.getBaseMachine().getOrThrow(AttributeEnergy.class);
                long expected = Math.round(Math.max(base.getConfigStorage() * 0.5, base.getUsage()) * tier.processes);
                check.accept(tile.getEnergyContainer().getCapacityAsLong() == expected, "ATM10 factory buffer " + tier + " " + type);
                index++;
            }
        }
        verifyUpgrades((TileEntityMekanism) level.getBlockEntity(START), 8000);
        verifyUpgrades((TileEntityMekanism) level.getBlockEntity(FACTORY), 12000);
    }

    private TileEntityMekanism place(ServerLevel level, BlockPos pos, String id) {
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        var block = BuiltInRegistries.BLOCK.getValue(Identifier.parse(id));
        check.accept(block != net.minecraft.world.level.block.Blocks.AIR, "balance fixture block registered " + id);
        level.setBlockAndUpdate(pos, block.defaultBlockState());
        return (TileEntityMekanism) level.getBlockEntity(pos);
    }

    private void verifyUpgrades(TileEntityMekanism tile, long baseCapacity) {
        tile.getComponent().addUpgrades(Upgrade.ENERGY, 8);
        check.accept(tile.getEnergyContainer().getCapacityAsLong() == baseCapacity * 16, "eight energy upgrades increase configured buffer sixteenfold");
        tile.getComponent().addUpgrades(Upgrade.SPEED, 8);
        check.accept(tile.getEnergyContainer().getCapacityAsLong() == baseCapacity * 16, "speed upgrades preserve independently configured buffer");
        tile.getEnergyContainer().setEnergy(12345, null);
    }

    private void verifySaved(ServerLevel level, BlockPos pos, long capacity) {
        level.setChunkForced(pos.getX() >> 4, pos.getZ() >> 4, true);
        var tile = (TileEntityMekanism) level.getBlockEntity(pos);
        check.accept(tile != null && tile.getUpgrades(Upgrade.ENERGY) == 8 && tile.getUpgrades(Upgrade.SPEED) == 8,
              "machine upgrades survive JVM restart at " + pos);
        check.accept(tile.getEnergyContainer().getCapacityAsLong() == capacity, "upgraded independent buffer survives JVM restart at " + pos);
        check.accept(tile.getEnergyContainer().getAmountAsLong() == 12345, "buffered energy survives JVM restart at " + pos);
    }
}

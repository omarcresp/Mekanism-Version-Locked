# ATM10 8.5 balance work

Compare effective FE, quantities and throughput, rather than equal raw config
values. ATM10 8.5 sets 2.5 Joules per FE. The port uses NeoForge energy directly.
Thermal heat quantities need separate treatment from electrical energy.

## Machine storage and usage

The `tools/atm11/atm10-8.5-balance.json` profile originally defined 73 machine settings: 36 independent
storage capacities, 35 usage/teleporter/consumption entries, the upgrade multiplier,
and SPS input cost. Source defaults and the disposable-copy migration apply these
values without a toggle. The migration preserves other settings and comments.

| Setting | ATM10 effective value | Previous target | Current validation |
| --- | --- | --- | --- |
| Enrichment usage | 20 FE/t | 5 FE/t | Full-pack base usage check passed |
| Enrichment buffer | 8000 FE | Usage-derived buffer | Full-pack capacity check passed |
| Energy upgrades | 16× buffer with all eight upgrades | 10× and usage-derived override | Full-pack machine/factory checks passed |
| SPS per input mB | 40000 FE | 1000000 FE | Exact bounded port insertion and antimatter conversion passed |
| MekaSuit base capacity | 16000000 FE | 16000000 FE | Equivalent config; equipment verification pending |
| MekaSuit charging | 80000 FE/t | 100000 FE/t | Pending |
| Solar generation | 60 FE/t | 2 FE/t | Source and pack profile restored; runtime evidence below |
| Advanced solar generation | 480 FE/t | 12 FE/t | Source and pack profile restored; runtime evidence below |
| Turbine storage per volume | 6400000 FE | 100 FE | Pending |

Independent capacities use the existing `withEnergyConfig(usage, storage)` API.
Energy upgrades increase the configured buffer; speed upgrades preserve it.
Factories retain ATM10's buffer scaling. The separator no longer replaces its
configured storage with a usage-derived buffer, and energy upgrades do not reduce
electrolysis cost. Separate electrolysis/fuel energy accounting remains necessary.
The default buffer for a machine without a specific storage setting is restored
to 400 ticks; resistive-heater thermal conversion still needs its own verification.

The first full-pack run passed 246 checks, including all 36 capacities, ordinary
machine usage, factory buffers, upgrade behavior, and bounded SPS energy insertion.
A different JVM passed another **58 checks**, preserving eight speed and energy
upgrades, 128000/192000 FE machine/factory buffers and exactly 12345 stored FE.
Both runs use identical mod hashes. See `machine-balance-runtime/` for evidence.

## Solar/wind output and generator storage

The profile adds nine settings from the published 8.5 TOMLs. Solar ratings are
60/480 FE/t and wind spans 60–360 FE/t. Generator buffers are heat 64000 FE,
bio 64000 FE, solar 38400 FE, advanced solar 1000000 FE and wind 80000 FE.
These are each the reference Joule quantity divided by 2.5. The actual release
sets advanced solar storage to 2500000 J; older rebalance notes differ.

The isolated generator fixture places disconnected generators, measures real
tick output and stored energy, checks wind at both height limits, covers the
generators to stop production, and saves 12345 FE in each buffer for restart.
Heat and bio production are deliberately outside this validation: their buffer
values are restored, while fuel timing and thermal accounting need further work.

The full-pack seed run passed **264 checks** and a fresh JVM passed **72 checks**,
including all six exact stored-energy checks. Mod hashes were identical.
See `generator-balance-runtime/` for results, process identities and log hashes.

## Fuel and generator work still pending

The fuel overlay still needs an electrical conversion. Legacy ethene is 20 burn
ticks at 300 J/t per mB: **2400 FE/mB**, not 6000 FE/mB. Hydrogen's 200 J/mB is
**80 FE/mB**. RC1 preserved numeric density (6000 and 200) in the new schema and
selected the port's integer burn limit. Existing fuel assertions describe those
RC1 values; update migration and runtime assertions together when equivalent
throughput and fractional consumption are implemented.

Remaining generator fuel/heat behavior, fusion/laser thermal conversion, equipment values,
other general settings, network capacities and pack recipes remain in the parity
inventory. Release TOML values take precedence over the older rebalance notes.

The [pinned 10.7.19.85 gas generator source](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGasGenerator.java)
limits work to `ceil(256 * tank fullness)` burn ticks per server tick, then caps
that by available fuel and free energy capacity. Ethene has 20 burn ticks/mB,
so a full tank can consume 12.8 mB/t and produce 30720 FE/t with the pack's
300 J per burn tick. The current integer mB limit cannot express this behavior.
The next fuel change must preserve partial fuel credit across transactions and
restart, keep chemical identity with that credit, and prove conservation when a
buffer has less than one complete mB's energy available. The legacy source also
has edge cases around empty tanks; those are not a reason to discard remaining
fuel credit in the port.

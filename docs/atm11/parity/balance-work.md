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
| Turbine storage per volume | 6400000 FE | 100 FE | Restored in source/profile; see `turbine-balance.md` for runtime verification |

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

## Fractional gas fuel implementation

The profile restores a gas tank of 18000 mB. Generated defaults and the pack
migration use ethene at 2400 FE/mB (20 burn steps) and hydrogen at 80 FE/mB
(one step). Both allow 256 steps/t at full pressure. Existing port maps retain
one step/mB unless they explicitly set `burnTicksPerMB`.

Ethene reaches 30720 FE/t with a full tank. Near-full energy buffers admit only
whole 120 FE burn steps: 37 FE free space stalls, while 157 FE permits 120 FE.
Fuel credit retains the remainder of the extracted mB, with chemical identity,
in both world saves and item components. The final credit continues burning
with an empty whole-mB tank. Extraction and FE insertion commit together.
See `../fuel-migration.md` for data format and migration behavior.

The isolated fixture checks pressure, whole-step output, exact fuel/FE
conservation, zero consumption when blocked, transaction rollback, normal loot
and replacement, and a fresh-JVM continuation with partial fuel credit.

The full-pack run passed **282 seed checks** and **80 fresh-JVM restart checks**
with identical mod hashes. Restart retained 17987 mB of ethene and 480 FE of
fuel credit in the full-pressure fixture, and 2280 FE of credit in the empty
partial-burn fixture. That fixture then generated exactly 2280 FE. See
`gas-balance-runtime/` for input hashes, process identities and assertions.

## Remaining balance and reactor work

Equipment values, other general settings, transport limits and pack recipes
remain in the parity inventory. Heat-generator conversion and bio fuel behavior
are now covered in `heat-bio-balance.md` and `heat-bio-runtime/`. In particular, ATM10 generators limit external output to twice their
configured/current production rate; the port's external output path needs its
own transport test and review. Turbine conversion and fractional persistence are
covered in `turbine-balance.md`. Disconnected production checks do not prove
that transfer limit. Release TOML values take precedence over older notes.

Reference: [pinned 10.7.19.85 gas generator source](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGasGenerator.java)
and [generator output implementation](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGenerator.java).

Reactor heat and resistive/fusion conversion are now covered in `reactor-thermal.md`
and `reactor-thermal-runtime/`. That unit verifies a physical ignition chain and
a controlled restart checkpoint; active/hot fusion persistence and safety remain
pending.

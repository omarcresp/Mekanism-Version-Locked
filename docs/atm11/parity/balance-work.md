# ATM10 8.5 balance work

Compare effective FE, quantities and throughput, rather than equal raw config
values. ATM10 8.5 sets 2.5 Joules per FE. The port uses NeoForge energy directly.
Thermal heat quantities need separate treatment from electrical energy.

## Machine storage and usage

The `tools/atm11/atm10-8.5-balance.json` profile defines 73 settings: 36 independent
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
| Solar generation | 60 FE/t | 2 FE/t | Pending |
| Advanced solar generation | 480 FE/t | 12 FE/t | Pending |
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

## Fuel and generator work still pending

The fuel overlay still needs an electrical conversion. Legacy ethene is 20 burn
ticks at 300 J/t per mB: **2400 FE/mB**, not 6000 FE/mB. Hydrogen's 200 J/mB is
**80 FE/mB**. RC1 preserved numeric density (6000 and 200) in the new schema and
selected the port's integer burn limit. Existing fuel assertions describe those
RC1 values; update migration and runtime assertions together when equivalent
throughput and fractional consumption are implemented.

Generator outputs/storage, fusion/laser thermal conversion, equipment values,
other general settings, network capacities and pack recipes remain in the parity
inventory. Release TOML values take precedence over the older rebalance notes.

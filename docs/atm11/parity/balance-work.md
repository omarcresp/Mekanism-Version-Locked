# Balance work remaining after reactor persistence

Use effective FE, quantities and throughput for parity, not equal raw config values.
ATM10 8.5 config sets 2.5 Joules per FE. The port removed that conversion and uses
NeoForge energy directly. Thermal heat quantities need separate treatment from
electrical energy; do not apply an electrical conversion to every `energy` key.

Representative electrical comparisons from the pinned release configs:

| Setting | ATM10 effective value | Current target value | Required work |
| --- | --- | --- | --- |
| Enrichment usage | 20 FE/t | 5 FE/t | Usage config |
| Enrichment buffer | 8000 FE | 20 FE (four ticks) | Restore independent storage configuration |
| MekaSuit base capacity | 16000000 FE | 16000000 FE | Already equivalent despite raw TOML difference |
| MekaSuit charging | 80000 FE/t | 100000 FE/t | Gear config |
| SPS per input mB | 40000 FE | 1000000 FE | Verify conversion through actual coil/port |
| Solar generation | 60 FE/t | 2 FE/t | Generator config and real generation |
| Advanced solar generation | 480 FE/t | 12 FE/t | Generator config and real generation |
| Turbine storage per volume | 6400000 FE | 100 FE | Generator config and actual transfer |

The fuel overlay requires a separate correction. Legacy ethene is 20 burn ticks
at 300 J/t per mB: **2400 FE/mB**, not 6000 FE/mB. Hydrogen's 200 J/mB corresponds
to **80 FE/mB**. RC1 preserved numeric energy density (6000 and 200) in the new
schema and selected the port's integer burn limit. That is decoding compatibility,
not effective energy or fractional consumption parity. Existing QA assertions
explicitly describe those RC1 numeric values; update both the migration and runtime
assertions together once equivalent throughput/consumption is implemented.

The old separate storage capability can be restored through the existing
`BlockTypeTile.Builder.withEnergyConfig(usage, storage)` API. Avoid a second
storage multiplier as a substitute: ATM10's individual capacities are not all the
same multiple of their usage. The release TOML values, rather than the older
rebalance notes, are the source of truth.

This document records pending work; these values have not been applied to the
current reactor runtime candidate.

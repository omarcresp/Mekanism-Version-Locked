# ATM10 8.5 heat and bio generators

Heat generation settings describe thermal Joules, while electrical containers
store FE. The pinned pack uses 2.5 Joules per FE. Bio generation describes direct
electrical output and therefore converts 350 J/t to 140 FE/t.

| Setting | Pinned ATM10 | Fork |
| --- | --- | --- |
| Bio output | 350 J/t | 140 FE/t |
| Bio item fuel | 200 mB, one mB/t | 200 ticks per item |
| Bio block fuel | 1800 mB | 1800 ticks per block |
| Bio tank | 24000 mB | 24000 mB |
| Active lava heat | 800 J per 10 mB | 800 thermal J per 10 mB |
| Adjacent lava heat | 30 J per adjacent side | 30 thermal J per adjacent side |
| Nether heat | 100 J/t | 100 thermal J/t |
| Lava tank | 24000 mB | 24000 mB |

A biofuel item yields 28000 FE when all 200 burn ticks fit. Blocks provide nine
items' worth of burn time. The existing transaction must fit all 140 FE before
consuming one mB. A 139 FE gap must preserve both stored FE and fuel.

The heat generator retains the reference's Carnot calculation, whole-Joule
rounding, and maximum thermal output. With these settings the cap is
800 + 7 × 30 + 100 = 1110 J, which converts to 444 FE. Whole FE enter the energy
container, and the fractional FE carries to the next tick. Heat and electrical
credit participate in the same transaction. Fractional credit persists in normal
world saves and a dedicated item component, so ordinary block loot and replacement
retain it. Older saves without this field default to zero.

The source defaults and disposable-pack migration contain the same pinned values.
The biofuel-per-item config uses its existing fuel-specific label and description.
No gameplay toggle is needed for the correction.

## Verification fixture

The isolated server places disconnected heat and bio generators. Controlled heat
pulses each produce one whole thermal Joule; three pulses must store one FE and
retain 0.2 FE. A high-temperature pulse checks the 444 FE cap. Other fixtures
account for 800 Joules from 10 mB of lava and 30 Joules from one adjacent lava
block, including environmental and conversion losses. The fractional fixture
also aborts a simulation and checks ordinary loot and replacement.

Bio fixtures check a complete 200-tick item, the first tick of an 1800-tick block,
and exact fuel rollback when 139 FE fit followed by a successful 140 FE burn.
The block then pauses for a controlled checkpoint. A second JVM must restore its
1799 remaining mB and 140 FE, then run exactly one further burn tick. Heat output
must restore its exact saved credit and combine two further one-Joule pulses into
one additional FE.

The final full-pack run passed **341 seed checks** and **116 fresh-JVM restart
checks**, with identical mod hashes and normal exits. The heat checkpoint held
1 FE and 0.20000000000000018 FE of credit; resumed pulses produced one additional
FE. The biofuel block restored 1799 mB and 140 FE, then resumed at 1798 mB and
280 FE. All tested Core, Generators and QA JARs matched their local build artifacts.
The main build passed 79 Java tests and the migration suite passed six Python tests.
See `heat-bio-runtime/` for assertions, results, input hashes and process evidence.

The first fixture assumed that the first global post-tick included every new tile.
The final fixture waits for all seven tile counters, applies inputs between ticks,
and verifies exactly one actual tile update before measuring. A subsequent attempt
exposed a missing generated loot entry for the new component. Running data
generation, then a separate main build and QA build, included the component in the
packaged loot table. The final seed proves actual pickup and replacement. Failed
worlds and evidence are archived outside this committed final proof.

The Nether setting is checked from the effective configuration; this fixture does
not place a heat generator in a Nether world. Generator output limits and real
receiver/network transfer are covered separately in `generator-output.md`.

## Reference

The [pinned baseline](README.md) records the official archive hash. Bio item/block
amounts and one-mB-per-tick consumption come from the official
[10.7.19.85 bio generator](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityBioGenerator.java).
The Carnot calculation and whole-Joule cap come from the official
[heat generator](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityHeatGenerator.java).

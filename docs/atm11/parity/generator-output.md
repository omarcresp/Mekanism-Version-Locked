# ATM10 8.5 generator automatic output

The reference generator base emits at most twice its configured or current
production basis per tick, shared across all eligible faces. The community port
emitted its entire stored buffer instead. The fork restores the shared limit in
the automatic emission call, using FE throughout electrical storage and transfer.

| Generator | Automatic output budget |
| --- | --- |
| Heat | 888 FE/t: twice the 444 FE electrical maximum |
| Bio | 280 FE/t: twice the configured 140 FE production |
| Solar | Twice the whole-FE biome-adjusted peak of the 60 FE rating |
| Advanced solar | Twice the whole-FE average peak across nine 480 FE panels |
| Wind | 720 FE/t: twice the configured 360 FE maximum |
| Gas, empty | 160 FE/t: twice the hydrogen basis |
| Gas, newly loaded ethene | 240 FE/t: twice the 120 FE burn step |
| Gas, after full-pressure ethene production | 61440 FE/t: twice actual 30720 FE production |

Gas output changes only when fuel insertion commits. An aborted fuel transaction
leaves the old limit intact. The generator updates its limit after a successful
burn, so the next tick uses that production basis. Empty generators return to
the hydrogen basis after the last fueled production tick. Saved fuel or partial
burn credit reconstructs the initial fuel-specific limit on loading, matching
the reference's transient limit rather than persisting a stale production rate.

This correction concerns automatic emission. Capability consumers can still pull
from the generator's exposed output container, as in the reference implementation.

## Runtime verification

A separate QA mod places seven generator fixtures and actual energy cubes in a
copied ATM11 0.14.0-beta server. Roofs prevent incidental wind and solar generation.
After all tiles have ticked, buffers are filled and ten consecutive updates must
match both the exact shared output budget and source/receiver conservation.
Advanced solar and wind each feed two physical faces. Another wind generator
feeds a cube through two physical universal cables across a chunk boundary.
The test asserts that the network discovers the cube and that energy reaches it.

Gas checks also abort fuel insertion, commit ethene, verify the dynamic output
limit after production, and account for simultaneous production and emission.
A second JVM checks saved source and receiver totals before resuming one cable
transfer with the same 720 FE budget. All earlier runtime fixtures remain enabled.

The first cable fixture changed the cube's raw ConfigInfo without sending the
normal capability notification. Diagnostics showed a running network with two
transmitters, 7200 FE in its shared buffer, zero local buffers, and no endpoint
acceptor. The corrected fixture sends sideChanged, as ordinary player/computer
configuration does, and explicitly checks receiver discovery. Failed worlds and
logs are archived separately from the final evidence.

The full pack passed **520 seed checks** and **142 fresh-JVM restart checks**
with identical mod hashes and normal exits. The cable receiver checkpoint held
8640 FE, then gained exactly 720 FE after restart while its source lost 720 FE.
The main build passed 79 Java tests and the migration suite passed six Python
tests. All tested Core, Generators and QA JARs matched the local artifacts.
`generator-output-runtime/` records assertions, results, input hashes, process
identities and the verification report. This unit does not establish reactor recirculation,
hot-state persistence/safety, equipment, pack progression or addon parity.

## Reference

The [pinned baseline](README.md) identifies ATM10 8.5 and Mekanism 10.7.19.85.
The automatic emission budget and shared output call come from the official
[generator base](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGenerator.java).
Dynamic limits come from the official
[gas generator](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGasGenerator.java).

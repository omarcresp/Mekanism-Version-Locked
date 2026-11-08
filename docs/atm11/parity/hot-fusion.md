# Hot fusion operation and persistence

An induction matrix could report committed input that had not reached its saved
cells. A seed checkpoint reported 163369620 FE while its physical cell saved
162936990 FE: exactly 432630 FE, the final input update, disappeared on restart.
The matrix now commits input and extraction directly to its cells in the same
transaction. Aggregate energy and throughput counters roll back with the cells.
Output is bounded by stored energy, and removing a cell subtracts its capacity
once. These production changes apply to every matrix.

The original fusion fixture proves physical laser ignition and a controlled cold
shutdown checkpoint. This separate fixture saves a reactor while it is hot and
burning, with real external fuel and electrical networks still connected.

The baseline remains ATM10 8.5 / Mekanism 10.7.19.85. The target is ATM11
0.14.0-beta, Minecraft 26.1.2, NeoForge 26.1.2.126 and Java 25.

## Physical circuit

A normal 5×5×5 fusion reactor has a laser focus matrix, two chemical input ports
and an electrical output port. Two external tanks begin with 50000 mB each of
deuterium and tritium. Separate tube networks fill the reactor through actual
ports. A powered laser supplies exactly 400000000 FE through a real amplifier
and focus matrix; the reactor consumes its filled hohlraum to ignite. No direct
heat injection or burning-state override ignites this fixture.

Five cables connect the electrical port to a normally formed 3×4×3 induction
matrix with one basic cell and one advanced provider. A matrix output port,
cable and energy cube exercise actual matrix ejection as well as input. The
matrix receives real fusion output during measured operation and cooling.
This does not certify induction tier balance or the full induction feature set.

## Independent ledgers

Each external fuel inventory includes its tank, connected tube network and
reactor input tank. At injection rate four, each feed supplies two mB per normal
reactor update. A separate counter records hot updates, including boot updates,
and checks the full fuel totals through final save. Measured operation also
checks exact consumption every fifty updates over a 200-update window.

The electrical inventory includes reactor storage, matrix storage, both cable
buffers and the external cube. A separate ledger uses measured heat lost to air, ATM10 thermocouple efficiency and the Joules-to-FE conversion, with
fractional carry. Every measured interval and final save must match this ledger.
The matrix must receive power through the actual output path.

## Restart and shutdown

The first normally formed restart observation must restore burning, injection
rate, hot plasma, hot casing and the fractional generation journal. Network
reconnection must preserve exact saved electrical inventory plus independently
counted boot generation, and exact saved fuels minus counted boot injection.
No replacement laser charge or hohlraum is supplied on restart.

After 200 more measured burning updates, the fixture sets injection to zero,
lets remaining mixed fuel burn out, and checks the reaction stops while both
external fuel inventories remain unchanged. The ledgers continue through the
remaining fission-repair tests and final save.

## Evidence and limits

Results, input hashes, distinct process records, scope and assertion transcripts
are recorded in `hot-fusion-runtime/`. The final run passed **739 seed checks and
320 restart checks** in separate JVMs with identical mod hashes. Data generation,
the main build, 79 Java tests, six Python migration tests and separate QA
compilation passed after the production fix. Both server processes exited
normally; the recipe/datamap error filters reported zero errors. The existing
cold-checkpoint fixture remains alongside this one.
Water-cooled fusion and physical fuel starvation are covered in
`water-cooled-fusion.md`. Interrupted operation and complete induction behavior (including mixed push/pull timing and tier balance), equipment,
full processing/progression and addon parity remain open in the broader ATM10 goal.

Before ignition, the live fixture checks immediate persistable cell updates,
aborted and nested-aborted transfers, shared input limits, committed extraction,
idempotent cell removal and exact capacity restoration. An active empty output
port must not create power. The later physical output circuit and aggregate
ledger check that ejection does not duplicate or lose energy.

The hot checkpoint contained 163369620 FE and 49046 mB of each fuel. The fresh
JVM restored the exact electrical and fuel totals after accounting for normal
boot updates, resumed burning for 200 measured updates, then stopped on zero
injection. Final storage totaled **606973665 FE**: 342781665 in the matrix,
256000000 in the cube and 8192000 in the cable networks. Each fuel ended at
48590 mB. The electrical and fuel ledgers matched exactly through final save.

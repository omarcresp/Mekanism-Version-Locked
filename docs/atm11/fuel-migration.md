# Chemical fuel migration

The parity baseline is ATM10 8.5 with Mekanism 10.7.19.85 and 2.5 Joules per FE.
The legacy map defines burn ticks per mB and Joules per burn tick. Ethene is
20 × 300 J = 2400 FE/mB; hydrogen is 200 J = 80 FE/mB.

The port keeps energy density in `energy` (FE/mB). `burnTicksPerMB` defaults to
one for existing port data maps, preserving their behavior. `maxBurnPerTick`
counts burn steps; divide it by `burnTicksPerMB` to obtain the maximum mB/t.
The ATM10 profile uses 256 steps/t, 20 steps/mB for ethene and one for hydrogen.
At full pressure ethene produces 30720 FE/t (12.8 mB/t equivalent).

The generator uses ATM10's `ceil(256 × tank fullness)` pressure curve, bounded by
available fuel and space for whole burn steps (120 FE per ethene step). A
37 FE gap waits; a 157 FE gap admits one 120 FE step. When consuming a whole mB produces
more energy than fits this tick, it retains the remainder as typed fuel credit.
That credit persists in world saves and block item components; the normal loot
table copies it. Credit finishes burning even when the whole-mB tank is empty.
A different chemical cannot enter the tank until existing fuel credit is spent.
Fuel extraction and FE insertion commit together; aborted operations do not
change credit. GUI production uses measured inserted FE, independent of rounded
burn-rate text.

`patch_pack.py` normalizes both legacy ATM overlays and RC1's previously migrated
6000/200 numeric densities to 2400/80 FE. Custom legacy entries are converted
from Joules and retain their burn duration; modern entries from other mods are
preserved. The migration is idempotent and applies only to marked QA copies.

Reference: [pinned gas generator source](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/tile/TileEntityGasGenerator.java).
The port conserves final partial credit rather than replicating the older
empty-tank edge case. Remaining thermal, equipment and pack parity are separate.

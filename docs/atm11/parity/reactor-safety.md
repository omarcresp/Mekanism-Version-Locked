# Fission coolant loss, recovery and meltdown

The baseline is ATM10 8.5 with Mekanism 10.7.19.85. Its normal damage algorithm
starts adding damage above 1200 K, caps the temperature contribution at 1800 K,
and considers a probabilistic meltdown at 100 damage while the reactor remains
hot. Manual shutdown stops burning but leaves residual heat. Cool reactors repair
over time. The port keeps that behavior; this fixture does not add an automatic
shutdown or change the meltdown configuration.

Reference: [pinned upstream fission implementation](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/content/fission/FissionReactorMultiblockData.java) and
[chemical radioactivity data](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/datagen/main/java/mekanism/common/MekanismDataMapsProvider.java).

Two distant physical reactors form normally in a disposable copy of ATM11
0.14.0-beta: a 5×5×5 recovery reactor with two fuel assemblies and a control rod,
and an 18×18×18 destructive reactor with eight fuel assemblies and four rods.
Fuel and emergency water enter through the actual input port capabilities.
No fixture directly sets heat, damage, meltdown probability, radiation or the
meltdown callback.

## Recoverable coolant loss

A dry reactor runs at 2 mB/t until it naturally exceeds 1200 K and accumulates
20 damage. The fixture shuts it down, checks ten actual updates consume no more
fuel, and verifies residual heat still increases damage. A full emergency water
charge then produces steam and cools the reactor below the damage threshold.
The server saves with nonzero damage and the reactor shut down.

A fresh JVM must restore the shutdown, burn setting, exact fuel/waste amounts
and nonzero damage. It waits for natural repair without consuming fuel, enables
the reactor, and checks 20 cooled updates consume exactly 40 mB without damage.

## Destructive coolant loss

The larger dry reactor receives a full 64000 mB fuel charge and runs at 8 mB/t.
Its shell extends beyond an individual blast, allowing the test to explicitly
require surviving casings before attempting cache recovery.
Normal ticking must trigger the configured random meltdown. The fixture checks
physical destruction, shutdown, radioactive tank clearing, heat reset, damage
reset to 75 and real world fallout. Unburned fissile fuel is nonradioactive in
the pinned upstream data map and survives, matching ATM10. After the explosion sequence ends, it
replaces damaged blocks while retaining surviving casing inventories. Normal
formation must recover the inactive damaged cache without resurrecting irradiated waste.
A fresh JVM must retain the exact surviving fuel, damaged shutdown and actual radiation source.

The connected water and sodium loops remain alongside these fixtures. Their
ledgers continue counting waste decay and generation after their measured
operation windows, until all safety tests finish and the server saves.

## Evidence and limits

The seed run passed **701 checks**. The recoverable reactor shut down at
1974.798 K and 20.143 damage. Residual heat raised damage to a peak of 21.643;
emergency water cooled it to 373.000 K. It saved inactive with 4338 mB fuel,
662 mB waste and 21.250 damage. The fresh JVM verified these quantities before the repair sequence changed them.

The destructive reactor reset to exactly 75 damage and 299.803 K, with its
waste cleared and 44184 mB nonradioactive fuel preserved. Real fallout measured
91.741 Sv/h at the probe position immediately after failure. After the explosion
sequence, 230 casings remained. Physical rebuilding recovered the old cache;
the reactor saved inactive with 72.082 damage after natural repair during the
remaining fixture updates.

The fresh JVM passed **292 checks**. Natural repair took 3063 observed updates,
then 20 cooled operating updates consumed exactly 40 mB, leaving 4298 mB fuel,
702 mB waste and zero damage. The rebuilt destructive reactor retained exactly
44184 mB fuel, remained inactive and continued repairing, with fallout restored.

Both connected loops finished with 2371 mB fuel, no stored waste and exactly
629 mB independently counted decay. Water and sodium inventories remained
conserved; the water-loop cube held 11950224 FE and the sodium-loop cube held
11931100 FE. Their generation ledgers include all updates through final save.
Both JVMs exited normally with identical mod hashes. The Core/Generators artifacts
match the unchanged production build, whose 79 Java tests remain retained; the
server QA compiled and all six Python migration tests passed for this unit.

Runtime results, process identities, identical input hashes and assertion logs
are recorded in `reactor-safety-runtime/`. QA stays outside production JARs.
These scenarios do not establish every possible accident, damaged chunk unload,
interrupted explosion persistence, hot fusion persistence or full ATM10 parity.

Fixture development failures are retained separately from passing evidence.
The first charge exceeded the small reactor capacity. Later assertions incorrectly
expected nonradioactive fuel to be dumped, and a small-shell rebuild attempted cache
recovery without verifying surviving casings. Saved NBT and the pinned upstream data
resolved those assumptions; production behavior was unchanged. The final fixture
requires real surviving casings before checking recovery of the original cache.

The first long restart also found a QA ledger edge case: the final decay of an
odd waste amount removes 1 mB, although the configured maximum is 2 mB. The
counter now uses the observed pre-decay amount, and explicitly checks that no
incoming waste can affect a partial final decay. Both JVM phases were rerun with
that corrected fixture and identical mod hashes.

# Connected water-cooled fission operation

The earlier reactor fixture proved burn, waste, thermal conversion and saved
nuclear contents inside a disconnected structure. This fixture adds the physical
transport and power circuit required for actual operation:

- An external chemical tank ejects fissile fuel through ultimate tubes and an input port.
- A second input port receives the initial water charge through its real capability.
- A coolant output port sends steam through a separate tube network to a formed turbine.
- Condenser vents return water through mechanical pipes to the reactor input port.
- A waste output port sends nuclear waste through tubes into the top of a waste barrel.
- A turbine energy valve sends FE through a universal cable into an energy cube.

Both multiblocks form through normal validation. The turbine has 28 physical
blades and condensers. No per-tick fuel, coolant, steam, water or energy injection
is used to sustain the measured operation. The initial coolant charge is the only
external water supply; fuel starts as 3000 mB in external storage.

## Conservation and restart

The seed runs 400 measured updates at one mB of fissile fuel per tick. Every fifty
updates must conserve total coolant across reactor water and steam, steam tubes,
turbine steam and condensate, and return pipes. The remaining fissile fuel, nuclear waste and counted barrel decay must still
total 3000 mB across both multiblocks, networks and external storage. Decay is
counted independently from each wrap of the barrel's exposed processing progress,
rather than inferred from a missing mass total. Reported turbine generation must equal aggregate stored FE in
the turbine, cable and cube. The reactor must retain coolant and zero damage.
Actual coolant receipt is detected when the reactor's water exceeds its previous
amount minus the current tick's boil rate.

The seed passed 621 checks. Its connected reactor consumed 400 mB of fuel,
leaving 2600 mB fissile fuel and 322 mB stored waste, with 78 mB independently
counted barrel decay. All 12500000 mB of coolant remained in the circuit. The
energy cube held all 5956536 FE of reported turbine production. The reactor had
zero damage and remained active at 375.6941352719923 K.

The seed finishes while this reactor remains active and hot. A fresh JVM checks
the active state and temperature on the first observed formed tick, before QA
changes it. Once networks have reconstructed, saved aggregate electrical energy
plus observed boot generation must equal the actual total; fuel and waste must
show continued burning while preserving their combined quantity. Another 200
measured updates must preserve the same invariants. Finally, shutdown must stop
fuel consumption for ten actual updates while the cooling loop remains intact.

The first fixture used an ordinary chemical tank as the waste receiver. It passed
all eight conservation checkpoints but failed the external waste receipt check:
ordinary tanks use the default attribute validator, which rejects radioactive
chemicals. The final fixture uses a radioactive waste barrel below the output
tube, feeding its supported top face. It preserves normal barrel decay and
includes the independently observed decay in the fuel/waste balance. The failed
world and logs are archived separately.

The fixture starts the seed's measured operation after the independent physical
fusion ignition checks finish, allowing the server to save immediately after the
active fission checkpoint. That sequencing preserves all previous fixtures and
avoids letting this reactor burn unattended for the laser's long charging period.

The fresh JVM passed **237 restart checks**. Its first observed formed reactor
restored active at 375.69392122197564 K. Startup burned another 29 mB before the
200-tick measured continuation. After shutdown the circuit held 2371 mB fissile
fuel, 501 mB waste and 128 mB cumulative decay, preserving the original 3000 mB.
All 12500000 mB coolant remained, and the cube held 10710792 FE. The reactor was
inactive with zero damage after ten shutdown updates.

Both JVMs exited normally with identical mod hashes. The tested Core and
Generators JARs match the unchanged production artifacts from the merged
fuel-chain unit; this change adds QA evidence without altering production code.
The main suite retains 79 passing Java tests, and the migration suite passed six
Python tests. Actual artifact hashes, process identities and assertion logs are
recorded under `connected-fission-runtime/`. Sodium/boiler recirculation is covered separately in `sodium-loop.md`.
This water-cooled proof does not establish coolant-loss or meltdown safety, hot
fusion persistence, equipment, full progression or addon parity. Those remain in
the active ATM10 8.5 parity inventory.

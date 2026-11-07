# Sodium reactor and boiler loop

The connected fission fixture now also builds a separate sodium-cooled reactor,
five-layer thermoelectric boiler and full turbine. It reuses the water-loop
measurements, fuel/waste handling and hot restart checks, with a second explicit
coolant topology in the QA mod. Production behavior and configuration are unchanged.

The reactor sends superheated sodium through tubes into a boiler input valve.
The boiler outputs cooled sodium through a separate tube network into a reactor
roof input port. Another boiler input valve receives the initial water charge
and condenser return water. A steam output valve supplies the turbine, whose
energy valve sends power through a cable to an energy cube. These are physical
ports, valves and networks; the multiblocks must form normally.

The initial sodium charge enters the reactor through its real chemical capability.
The initial water charge enters the boiler through its fluid capability. External
fuel storage supplies 3000 mB of fissile fuel through physical tubes. No per-tick
fuel, coolant, heat, steam or energy injection sustains the measured operation.

## Independent conservation checks

The reactor-to-boiler sodium inventory is independent of the boiler-to-turbine
water/steam inventory. Every fifty operating updates must preserve both totals:

- Sodium: reactor cooled/heated tanks, hot and cold tubes, and both boiler coolant tanks.
- Water: boiler water/steam tanks, steam tubes, turbine steam/condensate, and return pipes.
- Fuel: remaining fissile fuel, nuclear waste and independently counted barrel decay.
- Electricity: reported turbine production equals stored FE in turbine, cable and cube.

The fixture also observes cooled sodium entering the reactor and condenser water
entering the boiler after accounting for each machine's conversion rate. Both
returns and actual cube receipt must occur. The boiler must heat from sodium and
produce turbine steam while the reactor retains coolant and zero damage.

The seed measures 400 active updates, then saves while both connected reactors
remain active and hot. A fresh JVM observes the sodium reactor's first formed
tick, checks exact aggregate FE plus boot generation and conserved fluid/chemical
inventories, runs 200 more measured updates, and shuts down for ten updates.
The original connected water loop and all earlier fixtures run alongside it.

The seed passed **677 checks**, including all retained water-loop checks. Its
sodium reactor consumed exactly 400 mB fuel, leaving 2600 mB fuel, 322 mB stored
waste and 78 mB counted decay. The circuit retained all 12500000 mB sodium and
992000 mB water/steam. Its cube received all 5898256 FE of reported generation.
The reactor saved active at 373.14637135551675 K with zero damage; the boiler
was at 404.63082396782244 K. Both return paths were observed.

The fresh JVM passed **276 restart checks**, including the retained water-loop
restart. The sodium reactor first restored active at 373.14638724345895 K.
After boot, 200 measured operating ticks and ten shutdown updates, the circuit
still held 12500000 mB sodium and 992000 mB water/steam. Its cube held 10659228 FE,
with exact aggregate electrical accounting through startup and measurement.
Remaining fuel was 2371 mB, stored waste 501 mB, and counted decay 128 mB.
The reactor was inactive with zero damage, and both return paths remained valid.

Both JVMs exited normally with identical mod hashes. The tested Core/Generators
JARs match the unchanged production artifacts from the merged fuel-chain unit.
The main suite retains 79 passing Java tests and six migration tests passed.
Actual mod hashes, separate process identities, assertion logs and scope are
recorded in `sodium-loop-runtime/`. This does not establish
coolant-loss/meltdown safety, hot fusion persistence, equipment, full progression
or addon parity. Those remain in the active ATM10 8.5 goal.

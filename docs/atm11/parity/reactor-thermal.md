# ATM10 8.5 reactor thermal conversion

Electrical containers use FE. Heat capacitors and fusion plasma calculations
continue to use Joules, with the pinned pack's 2.5 J per FE. Converting every
config key containing “energy” would change reactor physics incorrectly.

| Quantity | Pinned reference | Fork value |
| --- | --- | --- |
| Fission fuel heat | 250000 J/mB | 250000 thermal J/mB |
| Fusion fuel heat | 10000000 J/mB | 10000000 thermal J/mB |
| Fusion output storage | 1000000000 J | 400000000 FE |
| Fusion fuel capacity | 500 mB | 500 mB |
| Thermocouple efficiency | 0.04 | 0.04, then J-to-FE conversion |
| Case conductivity | 0.333333333 | 0.333333333 |
| Water heating ratio | 0.27272727272727 | 0.27272727272727 |
| Default resistive heater input | 100 J/t | 40 FE/t |
| Resistive heat efficiency | 0.6 | 0.6, applied after FE-to-J conversion |

`HeatUtils` defines both directions of this conversion. Fusion laser input uses
FE-to-J conversion before changing plasma temperature. At the cold-plasma
multiplier, 400 million FE supplies one billion Joules and increases plasma
temperature by 100 million Kelvin. Resistive heating converts 40 FE to 100 J,
then produces 60 J at the configured efficiency.

Fusion thermocouple output follows the reference's whole-Joule rounding and
then converts those Joules to FE. The fraction of one FE persists alongside
plasma state; otherwise, a source producing one Joule per tick would lose its
entire electrical output when rounded each tick. The journal rolls temperature
and electrical remainder back together. The multiblock cache saves and restores
the remainder, and absent fields in older saves default to zero.

## Isolated runtime fixture

The fixture forms a normal fusion structure with a focus matrix and places a
powered laser and an amplifier on perpendicular beam paths. It supplies exactly
400 million FE to the powered laser in bounded 20000 FE increments. The laser
charges the amplifier through actual beams, and the amplifier fires its stored
400 million FE pulse through the focus matrix. Native tick sprint accelerates
20000 charging ticks; it does not skip charging or replace laser transport with
a reactor API call. The reactor must consume a filled hohlraum and sustain
100 burning ticks using deuterium and tritium.

Before ignition, three controlled thermal pulses each generate one whole Joule.
The fixture expects one FE stored and 0.2 FE remaining. It separately checks
that an aborted electrical-input transaction restores both plasma temperature
and the fractional output state. A disconnected resistive heater accounts for
stored heat plus environmental loss, expecting exactly 60 J from 40 FE.

For restart, the fixture makes a controlled, fuel-free checkpoint at ambient
temperature with 12345 FE, a 0.4 FE remainder and injection rate four. A fresh
JVM must restore those values. Two further one-Joule pulses must then add one FE
and retain 0.2 FE. This checkpoint verifies persistence of the new conversion
state; active, hot fusion persistence and safety are still separate checks.

The full pack passed **298 seed checks** and **89 fresh-JVM restart checks**,
with identical mod hashes. The seed run completed 20119 game ticks, including
20000 laser charging ticks and 100 sustained fusion burn ticks. See
`reactor-thermal-runtime/` for results, input hashes, process identities and log
hashes. Both production JARs and the QA JAR matched the local build artifacts.

## Remaining work

Heat-generator conversion, turbine generation/balance, external generator
transfer limits, full transport paths, active-reactor persistence, safety,
equipment, pack progression, addons and final client verification remain in the
parity inventory. This change does not establish full ATM10 parity.

Sources: [pinned fusion implementation](https://github.com/mekanism/Mekanism/blob/v1.21.1-10.7.19.85/src/generators/java/mekanism/generators/common/content/fusion/FusionReactorMultiblockData.java)
and the pinned 8.5 release TOMLs inventoried in `atm10-8.5-audit.json`.

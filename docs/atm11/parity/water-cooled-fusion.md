# Water-cooled fusion and fuel starvation

Baseline: ATM10 8.5 / Mekanism 10.7.19.85. Target: ATM11 0.14.0-beta,
Minecraft 26.1.2, NeoForge 26.1.2.126 and Java 25.

## Physical circuit

A second hot fusion fixture uses the same physical laser ignition, external
fuel feeds and induction output assertions as the air-cooled fixture. A normally
formed full-rotor turbine receives steam through a separate ultimate tube
network. Turbine vents return condenser water through ultimate mechanical pipes
to a real fusion input port. A turbine output valve, cable and energy cube
exercise actual electrical output.

The fixture admits one finite water charge through the real port after setting
injection to four. It then accounts for all water and steam in the reactor,
steam tubes, turbine, condenser and return pipes. No continuous external water
supply hides losses. Water capacity, steam capacity and casing-to-water heating
ratio match the pinned ATM10 configuration.

## Independent inventories and restart

Each fusion fixture tracks both fuel inventories, thermal generation and
fractional FE. The water-cooled fixture also includes the turbine's committed
production and physical electrical inventory. A hot checkpoint must restore
fuel and FE exactly after counted normal boot updates, then sustain 200 more
measured burning updates. The original air-cooled zero-injection shutdown
remains in the same run.

For the water-cooled fixture, shutdown instead disables the reactor-facing
connections on both physical fuel tubes using the transmitter's normal mode
change and connection refresh. Injection remains four. The stored reactor fuel
must run out and burning must stop without another hohlraum. Fuel remaining in
the external tanks and connected networks stays in the accounting inventory.
Keeping injection unchanged also avoids changing water/steam tank capacities
while measuring coolant conservation.

## Validation

The QA fixture compiled and the full-pack seed passed **783 checks** with a
normal server exit. The hot checkpoint contained exactly 4000000 mB of coolant
across the closed circuit, 49046 mB of each fuel and 563867545 FE. Of that energy,
477034344 FE came from the turbine; the remainder came from fusion thermocouples.
The return-pipe counter recorded 119258586 mB moved through the finite coolant
inventory. Plasma remained burning at 176195503 K with injection four.

A fresh JVM passed **358 restart checks**, giving **1141 full-pack checks**
across the pair. The first formed observation restored burning, injection four,
176298211 K plasma and 27965479 K casing, with no replacement hohlraum or laser
charge. Exact saved fuel and FE totals passed after independent accounting for
normal boot updates. Another 200 measured burning updates passed before the
physical fuel connections were disabled.

Stored fuel then ran out and burning stopped with injection still four. At final
save, both external fuel inventories totaled 48090 mB each, mixed fuel was empty,
and the closed coolant inventory remained exactly 4000000 mB. Total stored energy
was **2394844663 FE**, including **2033141696 FE** in the turbine output circuit.
The full electrical inventory matched saved energy plus 1830977118 FE generated
in the second JVM. The return network recorded another 389026838 mB moved through
the same finite coolant charge.

`water-cooled-fusion-runtime/` records results, identical mod manifests, distinct
process IDs, normal exits, artifact hashes and assertion transcripts. Recipe and
Mekanism datamap error filters reported zero errors. Production Core/Generators
JARs are unchanged from the preceding induction fix, with byte-for-byte local
artifact checks; its 79 Java and six Python test results are retained. The QA
JAR was rebuilt for this fixture. A console `tick sprint 4000` accelerated normal
world updates only after both fusion shutdown sequences passed, while the earlier
fission natural-repair fixture finished.

The wider goal still includes interruption/chunk scenarios, induction tier and
mixed transfer behavior, complete nuclear processing, equipment, progression and
Mekanism-specific addon parity. QA runs use copied servers and disposable worlds;
no production world or user's real client is changed.

The first attempt exhausted its eight-minute process deadline at update 5400,
during laser charging after a long pack startup. Its world and raw evidence were
preserved separately. No passing result is attributed to that attempt. The runner
now allows fifteen minutes per JVM; the successful seed began in a fresh world.

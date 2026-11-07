# ATM10 8.5 reactor fuel-chain balance

Twelve effective general settings still differed from the pinned pack after the
generator and thermal corrections. The source defaults and disposable-instance
migration now carry the same ATM10 values. Thermal quantities stay in Joules;
fluid and chemical quantities stay in mB.

| Setting | Previous port | ATM10 and fork |
| --- | --- | --- |
| Solar neutron activator peak basis | 64 mB/t | 256 mB/t |
| Filtered pump heavy water per source | 10 mB | 50 mB |
| Evaporation temperature multiplier | 0.4 | 1.85 |
| Evaporation heat capacity per layer | 100 J/K | 600 J/K |
| Boiler water per water-volume block | 16000 mB | 32000 mB |
| Boiler steam per steam-volume block | 160000 mB | 320000 mB |
| Boiler heated coolant per water-volume block | 256000 mB | 512000 mB |
| Boiler cooled coolant per steam-volume block | 256000 mB | 512000 mB |
| Waste-barrel processing interval | 20 ticks | 10 ticks |
| Waste-barrel decay per interval | 1 mB | 2 mB |
| Radiation source decay factor | 0.9995 | 0.9975 |
| Radiation target decay factor | 0.9995 | 0.9975 |

The neutron rate retains biome and sunlight modifiers. Evaporation retains the
height factor, environmental heat loss, maximum temperature and recipe ratios.
The barrel still respects the isotope decay blacklist. No processing algorithm
or capability API changes are needed for these effective settings.

## Physical runtime fixture

A separate QA mod builds a five-layer boiler with a real disperser plane and
superheating element, and a four-layer evaporation plant with a controller. Both
must form through their normal validation. The boiler's actual containers must
match the restored capacities. Water, steam and cooled sodium are filled to 75%
of their new capacity, above the old capacity, and a fresh JVM must restore the
exact totals without clamping. This is storage verification; it does not prove a
connected sodium cooling loop.

A filtered electric pump operates on a contained water source. Its first cycle
must produce exactly 50 mB of heavy water. The neutron activator processes actual
nuclear waste into polonium for one sunlit tick, with the biome-adjusted 256 mB/t
basis and exact ten-to-one consumption. A heated evaporation tick must consume
ten water per brine at the restored temperature/height rate, with 2400 J/K in the
four-layer structure. Machines then pause or empty their inputs so their output
can be checked on restart. The second JVM resumes another measured operation.

Two waste barrels run for twenty measured ticks: nuclear waste loses exactly two
mB at ticks ten and twenty, while polonium remains at 100 mB. The nuclear waste
fixture is emptied after this measurement; the isotope checkpoint persists.
Separate real RadiationSource and detached entity exposure objects each decay
100 to 99.75 using the effective settings, without irradiating the QA world.

The full pack passed **578 seed checks** and **206 fresh-JVM restart checks**,
with identical mod hashes and normal exits. The main build passed 79 Java tests
and the migration suite passed six Python tests. All tested Core, Generators and
QA JARs match the local artifacts byte for byte. Both phases retain all earlier
runtime fixtures. Assertion logs, results, mod hashes and process identities are
recorded in `fuel-chain-runtime/`.

The boiler restored exactly 744000 mB water, 7680000 mB steam and 12288000 mB
cooled sodium. The first measured operations produced 225 mB polonium and 1109 mB
brine; the fresh JVM restored those outputs and produced the same amounts again,
reaching 450 mB and 2218 mB. Both pump phases yielded 50 mB heavy water, and the
isotope barrel retained 100 mB polonium.
Connected fuel/coolant transport, boiler conversion, active hot reactor state and
safety remain separate work in the full parity goal.

## Reference

The official ATM10 8.5 archive hash and pinned Mekanism 10.7.19.85 version are
recorded in the [baseline](README.md). Its `config/Mekanism/general.toml` supplies
these effective values. The initial audit records those values and their source
file hash; this report records their implementation and actual processing scope.

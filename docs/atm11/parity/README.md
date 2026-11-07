# ATM10 parity baseline and verification

Baseline: **ATM10 8.5**, published November 4, 2026, Minecraft 1.21.1,
NeoForge 21.1.265, 498 manifest entries.
[Official release](https://www.curseforge.com/minecraft/modpacks/all-the-mods-10/files/9431424).
Export SHA-256: `ea56a46b478b634ac538dfdd4c7175b5e58f6f3e0df67d25373ff601c79b49ac`.
Target: ATM11 0.14.0-beta, Minecraft 26.1.2, NeoForge 26.1.2.126, Java 25.

Parity means Mekanism's functionality and relevant ATM progression/integrations,
including reactors, rather than adding every other mod from ATM10.
The baseline stays pinned for this work even if a later ATM10 release appears.
Its manifest pins Mekanism Core (project 268560/file 7904058), Generators
(268566/7904061) and Tools (268567/7904062), all version 10.7.19.85.
RC1 shipped Core and Generators; Tools deployment and validation remain part of
this parity goal.

## Current evidence

RC1 is a working initial compatibility release, not certification of ATM10 parity.
Its existing evidence covers enrichment/platinum processing, selected external
recipes, chemical capabilities and rollback, save/restart, quest visibility,
and equipment rendering. See the parent runtime evidence directory.

The first new full-pack fission test formed a real 5×5×5 structure without forcing
formation, burned fuel, produced waste and heated coolant, and remained undamaged.
That initial test used broad amount assertions; the subsequent fixture strengthens
these to exact fuel/waste quantities and a fresh JVM persistence check. The stronger seed assertions and turbine formation/steam/energy/condensate checks
passed. The first restart then exposed a heat serialization crash: the shared
capacitor codec serialized the heat value into the capacity field. Its constructor
also validated heat instead of capacity. Both source errors are fixed; three focused
regressions failed before the fix and all 74 unit tests pass afterward. The fresh full-pack run then passed **65 seed checks and 51 restart checks** in
different JVMs with identical mod hashes. Fission heat capacity remained exactly
98000, fuel/waste changed from 900/100 to 800/200 in the second operating cycle,
and reactor damage stayed zero. See `runtime/` for results, input hashes, process
reports and check transcripts. The initial fixed fixture supplied too little water;
the final fixture fills its real coolant capacity before each operating cycle.

| Area | Required proof | State |
| --- | --- | --- |
| Fission | Formation, exact burn/waste, cooling, ports, shutdown, save/restart, safety | Exact burn/waste, cooling, shutdown and fresh JVM persistence passed; external ports/safety scenarios pending |
| Industrial turbine | Formation, steam-to-energy, condensate, actual output, restart | Formation and internal conversion passed; external output/restart pending |
| Fusion | Hohlraum and laser ignition, sustained fuel injection, heat/energy, shutdown/restart | Hohlraum/API ignition, 100 ticks of injection and generation passed; laser/persistence pending |
| SPS | Coil/port energy, polonium-to-antimatter quantities/cost, output/restart | Real port/coil and exact 1 mB conversion passed; external transport/balance/restart pending |
| Nuclear progression | Fuel chain, plutonium/polonium, pellets, waste decay/radiation | Pending |
| Machines | Processing families, factories/upgrades, fivefold ore chain and pack metals | Selected processing passed; full inventory pending |
| Transport/storage | Cable/tube/pipe/item networks, induction, dynamic tank, QIO, chunk reload | Capability checks passed; network proof pending |
| Equipment | Modules, MekaSuit/tool mechanics, first/third person, ATM stats/recipes | Render evidence exists; mechanics/config parity pending |
| Pack progression | Quests, gated recipes, ATM star and creative crafts, external recipes | Selected checks passed; complete semantic diff pending |
| Addons | Compare actual baseline manifest dependencies against available target modules | Pinned server metadata inventory complete; missing Mekanism-specific addons identified, runtime parity pending |

## Module and integration inventory

`atm10-8.5-modules.json` records the official server archive (file 9431427,
SHA-256 `dfb34538715589f354c370101ffab3cbb2c3c8dcbc1dac6e382e8560b4a6db06`),
its 468 mod JARs, and the 20 JARs whose metadata declares a Mekanism dependency
or whose filename identifies a Mekanism module. Optional dependency declarations
are inventory evidence, not proof that an integration works. Integrations without
such metadata can exist and must also be covered by recipe/runtime checks.

The target currently lacks Tools, Applied Mekanistics, More Mekanism Machines
(`mekmm`), Mekanistic Routers, Mekanism Covers, the Refined Storage integration,
Gravitational Modulating Additional Unit and Just Enough Mekanism Multiblocks.
These Mekanism-specific gaps remain within the parity assessment. Other absent
parent mods, such as Advanced Peripherals or Ars Unification, require a separate
scope assessment; this goal does not imply porting all of ATM10.

Reproduce the inventory with:

```sh
python3 tools/atm11/audit_modules.py --baseline /path/to/ServerFiles-8.5.zip \
  --target /path/to/disposable-atm11 --output /tmp/atm10-modules.json
```

## Machine balance implementation

The first balance change restores 36 machine storage settings, ordinary electrical
usage, independent upgrade buffers and SPS cost, with 2.5 J converted to 1 FE.
The full pack passed 246 seed checks and 58 fresh-JVM restart checks. See
`balance-work.md` and `machine-balance-runtime/` for scope, results and remaining
fuel/generator/equipment work. The initial audit below remains a frozen comparison
against the RC1 target; it is not a claim that all listed differences remain open.

## Initial config findings

`atm10-8.5-audit.json` records reference file hashes and TOML key differences without
redistributing the pack's script contents. Reproduce with:

```sh
python3 tools/atm11/audit_parity.py --baseline /path/to/All-the-Mods-10-8.5.zip \
  --target /path/to/disposable-atm11 --output /tmp/atm10-audit.json
```

The initial scan found 361 files mentioning Mekanism and 485 differing/missing
settings across 15 config files. This is a review inventory, not 485 confirmed
bugs: it includes client-only files missing from a dedicated server and removed
settings. Actual release config values take precedence over the older explanatory
rebalance Markdown; for example, condenser rate is 128000 in the release config.

Confirmed settings needing semantic comparison include randomized consumption
(ATM10 false, target true), upgrade multiplier (16 versus 10), solar neutron
activator rate (256 versus 64), SPS energy per input, generator outputs and storage,
MekaSuit/tool stats, reactor efficiency, turbine flow/condensation, heavy water,
evaporation and waste/radiation decay.

Do not copy legacy energy numbers blindly. ATM10 uses Joules with 2.5 J per FE;
the port directly uses NeoForge energy and removed the conversion configuration.
The port also derives ordinary machine storage from usage with a multiplier of 4,
where ATM10 configured separate capacities. Matching both usage and buffering
therefore needs an explicit source-level storage decision, not just a TOML copy.

QA fixtures are separate from production JARs and only run in owned disposable
worlds. Client tests must retain `soundCategory_master:0.0` before and during launch.

## Runtime test boundaries

Structures form through normal multiblock validation. Fission, turbine and fusion
fuel/coolant setup uses tank APIs; this does not prove external pipe networks.
Fusion receives ignition energy through its reactor API, not a physical laser
network. SPS energy goes through an actual port and connected coil. Only fission
receives explicit save/restart assertions; the other structures remain in the
saved world but are not rebuilt or operated again by the restart fixture.
The verified run starts with a newly seeded world; recovery of heat data already
corrupted by a pre-fix save is not certified.

# ATM11 0.14.0-beta integration

This fork targets Minecraft 26.1.2, NeoForge 26.1.2.126 and Java 25. The validation pack is ATM11 **0.14.0-beta**. Other pack or Minecraft patch versions have not been validated. Runtime validation is in progress; there is no ready-to-play release yet.

## What the fork changes

- Fixes dynamic item ingredient decoding for the current recipe format.
- Builds an overlay that translates legacy Ars Magica Legacy and Productive Bees recipes and adds AllTheOres platinum processing.
- Restores MekaSuit OBJ loading, armor geometry and first-person arms from the community binary's MIT-licensed implementation, adapted to reproducible source. See [source provenance](render-source-provenance.json).
- Repairs modern quad unpacking/rebaking and keeps armor, glass and arms on the item texture atlas.
- Migrates the pack's creative energy component to a scalar long and its chemical fuel data to the current format; see [fuel units and limits](fuel-migration.md).
- Uses `MekaSuitModel` and `FlameRenderer` so Iris 1.11.4 does not apply obsolete mixins targeting pre-26.1 classes.
- Reveals the pack's two existing Mekanism quest chapters, removes their unavailable-mod placeholder dependencies, guards absent ExtraDisks chemical items, and repairs tooltip selectors in the local instance copy. Groovy slashy literals are strings; these selectors need regex Patterns with trailing wildcards.

No Iris JAR changes or gameplay feature flags are required. Production sound works normally; every local test client is muted.

## Build and prepare a disposable client

Use JDK 25 and the checked-in Gradle wrapper:

```sh
./gradlew --no-daemon --max-workers=2 runData
./gradlew --no-daemon --max-workers=2 build
python3 tools/atm11/prepare_instance.py \
  --source /path/to/ATM11/minecraft \
  --target /path/to/new-disposable-minecraft
```

The target must not already exist. The script copies mods, configuration, scripts, shader packs and resource packs. It copies no player worlds or launcher accounts, replaces Core and Generators, generates the overlay, fixes tooltip selectors, reveals the two quest chapters and removes their placeholder gates, guards absent ExtraDisks chemical items, migrates fuel data and the creative cube component, and sets master volume to zero before first launch. Launch this copy using a production NeoForge 26.1.2.126 profile with Java 25. A development launch does not faithfully reproduce this pack's production behavior.

A specific extra local `ancestrallies-0.1.0-alpha.1.jar` pins NeoForge .124. The preparation script disables that file only in the disposable copy. Rebuild it for .126 before using it alongside the target pack.

## Server integration

Generate the overlay from the target pack's installed mods:

```sh
python3 tools/atm11/build_compat.py --mods /path/to/ATM11-0.14/server/mods
```

Install these three artifacts on both server and client:

- `build/libs/Mekanism-26.1.2-10.8.0.homebaked.jar`
- `build/libs/MekanismGenerators-26.1.2-10.8.0.homebaked.jar`
- `build/atm11/atm11-mekanism-compat-0.1.0.jar`

Replace previous Core/Generators versions rather than keeping duplicate mod IDs. Do not install QA JARs on a playing server. Clients also require the tooltip selector repair described above. Both sides also require migration of `kubegroovy/dynamic_data/mekanism/data_maps/mekanism/chemical/chemical_attribute_fuel.json` and the creative cube component in `kubegroovy/server_scripts/progression/atm_star_creative.groovy`; the pack repair script handles these. On the server, reveal the existing quest chapters by changing `always_invisible: true` to `false` in `config/ftbquests/quests/chapters/mekanism.json5` and `mekanism_reactors.json5`. Also remove dependency references to the hidden NOT HERE YET quests `132777D37FD5E38B` (Mekanism) and `48195ED3F9E729BE` (Reactors), preserving all other dependencies. Changing chapter visibility alone leaves the entire quest chain hidden. The instance preparation script applies these changes to copied clients. The recipe-viewer script must also guard its three `extradisks:infinite_chemical_storage_*` removal calls with `$Item.exists(id)`, since those optional items are absent in the target version. For marked disposable server copies, run `python3 tools/atm11/patch_pack.py /path/to/server`.

The overlay reads the user's installed Ars Magica Legacy and Productive Bees JARs at build time. Their recipe resources and ATM pack scripts are not redistributed in this repository. Translation preserves quantities and conditions; dynamic recipes also require this fork's codec fix.

## Evidence and limits

The current source build passes 71 upstream unit tests. The exact candidate passed two full-pack dedicated-server runs with 42 seed and 40 fresh-JVM restart assertions. Machines consumed energy and inputs, enriched carbon and AllTheOres platinum processing produced exact quantities, and inventories plus 1,000 hydrogen persisted across restart. Actual pack trash cans accepted chemical insertion, and aborted transactions restored their original chemical amount.

Both runs loaded 41,299 recipes with zero recipe decoder errors, recipe-event failures or Mekanism data-map failures. The checks include both migrated fuel entries and the creative energy cube progression recipe. The community binary previously logged 257 distinct invalid recipe IDs; earlier POC runs only checked decoder errors and missed the fuel and recipe-event problems. See [current runtime evidence](fork-runtime/validation.json) for artifact hashes, JVM process IDs and assertion results.

The unmodified pack has unrelated log errors. Reactor and SPS registration were tested; operation was not. Existing ATM10 world migration, complete ATM10 progression parity, enabled shader packs and every module animation have not been validated. Fuel energy density is preserved, but the new burn-rate model does not reproduce legacy peak throughput or fractional burn timing.

[Initial POC evidence](server-poc-evidence.json) predates the rendering repair and explicitly records that earlier client failure. The source snapshot before fork changes is `8bb9b2cc6735e0e86d536a4857e39c5ed122c5e7`. The [QA instructions](../../qa/README.md) describe the separate fixtures and fresh-JVM runtime checks used to validate later revisions.

See the [port assessment](assessment.md) for the source gaps found and the remaining certification work.

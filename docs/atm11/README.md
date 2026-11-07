# ATM11 0.14.0-beta integration

This fork targets Minecraft 26.1.2, NeoForge 26.1.2.126 and Java 25. The ATM11 validation target is 0.14.0-beta. Other 26.1 patch releases have not been validated.

## Initial server evidence

The source build passed 71 upstream unit tests and two full-pack dedicated-server runs with 38 seed and 36 fresh-JVM restart assertions. Machines consumed energy and inputs, enriched carbon and AllTheOres platinum processing produced exact quantities, and inventories plus 1,000 hydrogen persisted across restart. Actual pack trash cans accepted chemical insertion, and aborted transactions restored their original chemical amount.

The community binary logged 257 distinct invalid recipe IDs. The patched source plus generated recipe overlay loaded 41,296 recipes with zero recipe parsing errors. The unmodified pack has unrelated log errors. Reactor and SPS registration were tested; operation was not.

The original source build fails client initialization with Iris 1.11.4's obsolete MekaSuit mixin. This branch is under development and has no ready-to-play release yet.

## Build

Use JDK 25 and the checked-in Gradle wrapper:

```sh
./gradlew --no-daemon --max-workers=2 runData
./gradlew --no-daemon --max-workers=2 build
python3 tools/atm11/build_compat.py --mods /path/to/ATM11-0.14/server/mods
```

The resource overlay is built from the user's installed Ars Magica Legacy and Productive Bees JARs; their recipe resources are not committed here. It translates retired recipe fields, preserves quantities and conditions, and adds four platinum recipes. Dynamic recipes also require this fork's ingredient codec fix. The overlay belongs on both server and client alongside Core and Generators; no pack script edits are required.

The source commit before fork changes is `8bb9b2cc6735e0e86d536a4857e39c5ed122c5e7`. The QA fixture was separate from production JARs. The checked-in JSON summarizes the first POC, including its known client failure; it does not certify later revisions.

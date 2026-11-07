# Isolated ATM11 validation

QA JARs are test fixtures. They are never packaged into Mekanism's production JARs or release bundle.

## Client rendering

Build the main project first, then:

```sh
./gradlew -p qa/client --no-daemon --max-workers=2 build
```

Install `qa/client/build/libs/atm11-client-qa-0.1.0.jar` only in a disposable ATM11 0.14.0-beta test instance, with the fork's Core, Generators and generated resource overlay. Use the real production NeoForge .126 runtime. The fixture observes the actual title screen, then waits for a disposable single-player world so item components are bound. It checks registered OBJ geometry, texture atlas agreement, part selection, all four armor slots and first-person arm output through real rendering callbacks. A recording vertex consumer checks finite coordinates and counts geometry; it does not replace visual inspection of an in-world player.

Results are written to `client-render-result.json` in the test instance. A successful launch alone does not count: inspect `success` and every asserted behavior. The fixture leaves the client open for native UI inspection. In a disposable single-player world, it equips the player with all four MekaSuit pieces, selects the front third-person camera, and writes `client-world-result.json` after 200 client ticks. Inspect the actual armor visually as well; the world result verifies synchronized equipment and mute state, not pixel correctness.

The user's extra Ancestrallies alpha.1 build pins NeoForge .124 and must be rebuilt or excluded from the disposable .126 test copy. Do not change the updated pack's loader to .124.

## Dedicated server save/restart

```sh
./gradlew -p qa/server --no-daemon --max-workers=2 build
```

The server fixture writes its result to `seed-result.json` or `restart-result.json` and stops the server. Before loading this fixture, prepare an isolated server bound to `127.0.0.1` with a fresh disposable world and create `poc-owned-world` in its game directory. Never install it on a playing server. Its marker is a QA ownership assertion, not a gameplay feature switch.

Run the same production mod files in two separate JVMs. The first run places and processes the fixture machines; the second verifies exact saved quantities before doing another processing operation. Compare input hashes and process IDs as well as checking `success` in both result files.

## Audio preference

Every test client must start with `soundCategory_master:0.0` in its isolated `options.txt`. The client fixture also keeps master volume zero and asserts the muted value. This is a test-machine preference; production gameplay sound is unchanged.

Run and retain the dedicated-server evidence:

```sh
python3 qa/run_server.py --server /path/to/disposable-server --output /path/to/evidence --phase seed --java /path/to/jdk25/bin/java
python3 qa/run_server.py --server /path/to/disposable-server --output /path/to/evidence --phase restart --java /path/to/jdk25/bin/java
```

The runner checks result success, matching process IDs, distinct JVMs, unchanged mod hashes, and recipe parse errors. It does not treat a clean process exit as sufficient evidence.

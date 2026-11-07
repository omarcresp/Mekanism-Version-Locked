# Local development instructions

- Always mute test clients before launch: set `soundCategory_master:0.0` in the isolated instance's `options.txt`. Keep master volume zero during every client test. Do not change the user's system volume or another running instance.
- Keep QA fixtures separate from production JARs. Test only disposable worlds and copied instances.
- Do not introduce gameplay feature flags, allowlists, environment toggles or gradual rollouts unless explicitly requested.
- The concrete ATM11 validation target is 0.14.0-beta, Minecraft 26.1.2, NeoForge 26.1.2.126, Java 25. Record what was tested before declaring compatibility.

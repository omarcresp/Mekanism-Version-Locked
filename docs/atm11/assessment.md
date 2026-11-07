# Port assessment

The POC establishes a source-built path for ATM11 0.14.0-beta on Minecraft 26.1.2 and NeoForge 26.1.2.126. This work requires a maintained Mekanism fork plus a generated recipe overlay and a few pack script/data migrations. An overlay alone cannot fix the ingredient codec or incomplete renderer.

The largest source gap was rendering. The community source snapshot did not reproduce the released binary's OBJ and armor implementation. Recovering the MIT-licensed implementation exposed further defects: untextured OBJ faces, item/block atlas disagreement, empty quad unpacking and lost rebake metadata. Iris also targeted obsolete class names. The fork now builds these repairs from source and keeps Iris unchanged.

Pack integration has separate boundaries. Legacy Ars and Bees resources need schema translation; dynamically generated recipes need the codec repair. Tooltips use Groovy Patterns, two existing quest chapters are hidden behind both chapter visibility and unavailable-mod placeholder quests, optional ExtraDisks chemical IDs must be guarded, fuel attributes use different units, and the creative cube component changed from a container object to a scalar long. The fuel migration preserves energy density, with current generator throughput semantics.

The server evidence checks actual processing, input and energy consumption, exact output quantities, save/restart persistence, chemical transactions, trash-can capabilities, selected external recipes, loaded fuel attributes and the creative progression recipe. It rejects recipe decoder, recipe-event and Mekanism data-map errors. See [the exact runtime record](fork-runtime/validation.json).

Remaining work for a broad production certification includes operating fission, fusion and SPS multiblocks; radiation and waste handling under load; factory upgrades and transport networks; the complete ATM star progression; enabled shader packs; and module-specific armor animations. Existing ATM10 world migration is a separate undertaking. The present POC cannot substantiate a completion-time estimate for those areas or promise full ATM10 parity.

The development loop follows the Ancestrallies approach: generate data, build production artifacts, compile separate QA fixtures, run a real production pack in disposable copies, restart in a fresh JVM and retain hashes and evidence. Test clients remain muted before launch and throughout every run. QA fixtures never ship in production bundles.

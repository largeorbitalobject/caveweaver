# Neural Karstogenesis runtime preview

This server-side Fabric mod applies one immutable learned cave package to one preflighted Minecraft 1.21.1 world seed and site. It is a release-shaped research preview, not a general cave-generation release.

## Runtime contract

The runtime:

- verifies the package identity, target-register digest, translation, seed and dimension before writing;
- treats the post-feature action and prior state as bound compatibility evidence;
- accepts live air as an already satisfied target and writes `minecraft:cave_air` only to live, fluid-free blocks in Minecraft's standard overworld carver-replaceable tag;
- preserves host biome identity, ordinary cave decoration, structures, block entities, and fluids;
- verifies one connected surface-entrance register with exactly one protected-route anchor;
- repairs protected-route cells occupied by blocks in the extensible `geoworld-karst-runtime:protected_closure_replaceable` tag after the audited feature window completes;
- repairs registered surface-entrance cells occupied by blocks in the narrower `geoworld-karst-runtime:surface_entrance_replaceable` tag, excluding registered static seals and route cells already handled by the route repair path;
- does not force generation, stop the server, or write biome and structure registries;
- disables itself on the first ownership or identity conflict and records a compact status receipt.

Other mods may add non-owning cave decoration to the shared `geoworld-karst-runtime:protected_closure_replaceable` block tag through a data pack. Surface providers may extend `geoworld-karst-runtime:surface_entrance_replaceable` independently. Optional tag entries do not create hard mod dependencies. Blocks with inventories, fluids, or unregistered ownership are never admitted by either repair path. The bundled compatibility entries cover vanilla leaves, logs, snow, and the optional Underground Worlds icicle in their registered contexts.

## Preview scope

Each supplied configuration is fixed to one seed, translation, package identity, target register, hydraulic policy and surface entrance. Run 550 supplies the first RC10 entrance-closure build contract.

Chunk application is fail-closed but not package-atomic across an arbitrary streaming order. A conflict discovered in a later chunk can leave earlier admitted chunks applied. The release claim therefore covers only this exact preflighted package, seed, site, vanilla host, and bounded audit window. General seed selection, arbitrary modpack ownership, multiplayer throughput, and geological population truth remain unproven.

## Layout

- `mods/` contains the Fabric runtime JAR.
- `config/` contains the pinned runtime configuration.
- `packages/q75/runtime/` contains the immutable cave package.
- `packages/q75/target-register.tsv` contains the exact host-state binding.
- `receipts/karst-runtime-status.json` is written by the running server.
- `release-manifest.json` and `SHA256SUMS` bind the preview files.

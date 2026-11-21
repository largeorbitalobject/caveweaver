# CaveWeaver

The Minecraft side of [neural-karstogenesis](https://github.com/largeorbitalobject/neural-karstogenesis). The cave shapes are computed offline, so the server mod is small (83 KB) and deterministic, and it runs no model or GPU code on the game thread.

CaveWeaver is the Minecraft runtime for deterministic, learned cave packages. It loads engine-neutral cave geometry, resolves host ownership, preserves structures and cave biomes, and applies bounded chunk transactions with reproducible evidence.

## Repository scope

- Fabric 1.21.1 runtime
- package and release verification
- deterministic caching and chunk-local consumption
- structure, fluid, biome, and competing-generator coexistence
- server release configuration and operational evidence

Model training and research live in the sibling `neural-karstogenesis` repository. Large packages, worlds, screenshots, and release evidence are kept offline in my lab archive and referenced here by manifest and SHA-256.

## Layout

- `runtime/`: Fabric runtime source
- `integrations/`: compatibility, transaction, biome, provider, multiplayer, and performance fixtures
- `schemas/`: consumed package and release contracts
- `tools/`: independent package verifiers
- `configs/`: frozen CaveWeaver execution bindings
- `deploy/`: isolated server release definitions
- `docs/`: repository boundary and architecture notes

## Contract

CaveWeaver consumes a pinned immutable cave package produced by Neural Karstogenesis. Package identity includes the schema identifier, topology-plan hash, payload hash, and release manifest. An unknown or conflicting package fails closed.

## Status

The research runtime has completed deterministic package loading, bounded live-world transactions, host ownership arbitration, cave-biome retention, multiplayer exploration, and matched-vanilla performance gates. Production mod packaging and broader third-party compatibility certification remain explicit release work.

## License

Apache License 2.0. See `LICENSE`. Minecraft and Fabric are the property of their owners; this repository contains none of their code.

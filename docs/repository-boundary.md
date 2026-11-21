# Repository boundary

## Neural Karstogenesis

Scope:

- real-cave corpus acquisition and normalization
- topology, trajectory, wall, hydrology, and morphology models
- neural evolution and constrained generative research
- training, evaluation, preregistration, and research records
- engine-neutral package schemas and reference writers
- game-neutral examples and portability validation

Excluded:

- Minecraft callbacks and mixins
- direct Anvil or live-world mutation
- mod interoperability policy
- player release packaging

## CaveWeaver

Scope:

- Fabric runtime and Minecraft-facing adapters
- package verification, caching, and deterministic chunk-local reads
- ownership arbitration for structures, fluids, cave biomes, and other generators
- bounded transactions and rollback evidence
- release manifests, server operation, and performance gates
- player-facing documentation and compatibility fixtures

Excluded:

- model training corpora
- experimental checkpoints
- research run artifacts outside release evidence
- training-only dependencies

## Shared contract

The shared contract is a pinned cave package schema plus its verifier fixtures. Neural Karstogenesis produces the package. CaveWeaver consumes it. The consumer pins a schema identifier and content hash so changes cannot silently cross the boundary.

## Data policy

GitHub contains source, tests, small fixtures, schemas, manifests, and selected figures. Orbital contains raw datasets, checkpoints, generated worlds, full run evidence, and visual captures. No secret, access token, server credential, or private raw provider archive belongs in Git history.

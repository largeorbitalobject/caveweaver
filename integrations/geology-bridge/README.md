# GeoWorld geology density bridge

This isolated Fabric mixin consumes a pre-generated, immutable signed-distance tile. It is disabled unless
`-Dgeoworld.geology.config=<absolute JSON path>` is present. Missing tiles fall back permanently
to legacy density for that server process, so explored chunks cannot change when a file appears
later. `coexist` is retained for flooded-geology research. `post_noise_air` is retained only as
a rejected diagnostic path: applying air at any per-chunk generation hook allowed neighboring
feature passes to observe already-carved chunks, and a paired block audit found baseline air
becoming solid.

The promoted staging path is `finalized_region_air`. The bridge remains inert throughout normal
chunk generation. After every target chunk and a cache-declared halo are loaded at FULL, an
operator invokes `geoworld_geology_apply`. The command preflights the entire target-plus-halo set
without generating missing chunks and makes no changes if any chunk is absent. Only after that
preflight does it enumerate negative-SDF blocks and write air. Positive SDF cells are never
written. A requested block directly supporting a gravity-affected block is retained, preventing
the learned cave mask from destabilizing sand, gravel, or concrete-powder columns. Re-running the
command is idempotent.

The bridge samples surface-relative depth from Terrain Diffusion's own base density
(`surfaceHeight - y`), leaves the top configured protection shell unchanged (20 blocks in the
v1 exporter, including interpolation margin), and takes the
minimum of base density and negative cave SDF. Minecraft generation threads perform no model
inference. The finalized application is an explicit, bounded main-thread staging phase, not a
worldgen callback. Both air paths use a hash-locked 64x64 surface tile
exported from the paired baseline, so its surface-relative coordinates do not depend on mutable
runtime heightmaps.

See `docs/GEOLOGY_TWO_PHASE_APPLICATION.md` for the release workflow and acceptance gates.

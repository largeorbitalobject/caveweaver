# Terrain Diffusion Fabric bridge integration

The deployed bridge is a bytecode-reconstructed mod without an original Git/Gradle source tree. Its canonical reconstructed sources and build notes are currently on CT221 under `/opt/td/hydro/modfix`; deployed worlds must not be modified during A0 development.

The A0 change extends only the existing raster contract:

- old three-field legend/raster configurations retain their behavior;
- `geoworld.coarse-raster.v1` requires all five physical fields;
- Java writes temperature standard deviation to internal plane 2 as `degC × 100` exactly once;
- precipitation CV is written unchanged to plane 4;
- the existing signed-square-root elevation encoding remains unchanged;
- the classifier-input dump is used to verify all five values live.

`apply_candidate.py` verifies the canonical reconstructed source SHA-256 and writes a transformed copy into a new staging tree. It refuses unrecognized source or an existing destination. The accompanying `SyntheticMapFactoryMixin.java` is compiled into that same isolated tree. Compile against the deployed 1.21.1 intermediary/JAR dependencies, run standalone legend/five-field/fallback tests, compare all untouched JAR entries, then run a clean isolated Minecraft world.

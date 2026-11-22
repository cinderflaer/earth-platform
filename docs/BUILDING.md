# Building and testing

The mod is one Gradle project in `fabric/`, which builds the Fabric jar. It also compiles the pure Java code in `geography/` as an extra source folder, so there is a single jar. Helper scripts (`modpack.py`, `gradle.sh` and the data scripts) are in `scripts/`.

## What you need

- JDK 25, needed to build and to run. Set `JAVA_HOME` to it.
- Gradle 9.8.2 (CI uses the same version), through the wrapper in `fabric/` or your own install. A fresh clone has no `gradle-wrapper.jar` (the `*.jar` ignore rule catches it), so run `gradle wrapper` once in `fabric/` or call an installed Gradle directly.
- The Fabric Loom plugin (`net.fabricmc.fabric-loom`, 1.19.x), which the build applies.
- No mappings to download: Minecraft 26.2 uses Mojang's names directly, so there is no Parchment.
- Network access to Maven Central, the Fabric maven host, and Mojang's download hosts. Gradle fetches Minecraft 26.2 and the Fabric toolchain.
- Three files you download yourself, because the mod builds against them but never bundles them. Every build checks their SHA-256 first:

| Environment variable | File | SHA-256 |
| --- | --- | --- |
| `EARTH_INSTALLED_FABRIC_IRIS_JAR` | [iris-fabric-1.11.4+mc26.2.jar](https://cdn.modrinth.com/data/YL57xq9U/versions/gxZWWnKH/iris-fabric-1.11.4%2Bmc26.2.jar) | `f1f7ab57c974d193ba33aa285864a0ded949216f402116fceaf4dc7739b4dd7c` |
| `EARTH_INSTALLED_FABRIC_SODIUM_JAR` | [sodium-fabric-0.9.2+mc26.2.jar](https://cdn.modrinth.com/data/AANobbMI/versions/xJZxADzI/sodium-fabric-0.9.2%2Bmc26.2.jar) | `16a5e91db49750f8c046ecca3b8f2af5a28ea6f9b6581b42f123ca8fda20864f` |
| `EARTH_BUG_COURIER_FABRIC_JAR` | [bugcourier-fabric-1.21.1-0.1.0.jar](https://github.com/pirut/mc-mods/releases/download/bug-courier-v0.1.0/bugcourier-fabric-1.21.1-0.1.0.jar) | `d082b37978957872c2ef681660c6ce069f4964a31d8e8d444c5f548c484e85ab` |

Set each variable to the file's absolute path, or pass the same paths as `-PinstalledFabricIrisJar=`, `-PinstalledFabricSodiumJar=` and `-PbugCourierJar=`. Bug Courier is compile-only: the jar is not bundled, and the game runs without it. It stays the 1.21.1 jar.

## Build and test

From `fabric/`:

```sh
gradle --no-daemon build
```

This compiles the mod, runs the unit tests and builds the jar. The jar is `fabric/build/libs/earthplatform-fabric-<version>.jar` (for example `earthplatform-fabric-0.1.0-alpha.38.jar`; the version is `mod_version` in `fabric/gradle.properties`). That one file is the whole mod. `scripts/gradle.sh` runs the wrapper in `fabric/` and passes an `HTTPS_PROXY` on to Java when one is set.

The unit tests run in a couple of minutes, and all should pass. They include `GlobeTest`, a harness for the Earth data code that serves synthetic tiles from a local HTTP server, and `GithubBugLinkTest`, which checks the prefilled bug-report link. GitHub runs the same build and tests on every pull request (`.github/workflows/ci.yml`). Publishing is separate: a pull request labeled `build` that merges into `main` releases the newest build, and when the repository has a `MODRINTH_TOKEN` secret that release uploads the jar to Modrinth (`.github/workflows/release.yml`). How to open that pull request is in [Shipping a build](release/RELEASE-DAY.md#shipping-a-build).

Headless checks, with `EARTH_BUG_COURIER_FABRIC_JAR` set: `runServer` boots a dedicated server without Bug Courier, and `runServerCourier` boots one with the jar copied into that run's `mods` folder.

## Try it in game

Install the jar, Fabric API 0.161.0+26.2 or newer and Fabric Loader 0.20.1 or newer into a Minecraft 26.2 instance (Java 25) and create a new Earth world. Use a fresh world after any change to terrain generation, because a world keeps the data choices it was made with. Downloaded Earth data lands in `earthplatform/cache` inside the game folder.

## Loader code

Shared code reaches the loader only through `io.earthplatform.minecraft.platform` (`Loader`, `Platform`, `ClientPlatform`, `Payloads`), which the Fabric side fills in through `META-INF/services`. The Fabric side lives in `fabric/src/main/java/io/earthplatform/minecraft/fabric` (Fabric API callbacks and the mixins for the hooks, in `earthplatform.fabric.mixins.json`). Config files use NeoForge's config classes, through [Forge Config API Port](https://github.com/Fuzss/forgeconfigapiport) 26.2.1, bundled in the jar. Shared mixins that target a lambda list both its NeoForge name and its Fabric intermediary name, and build against the oldest Fabric Loader the jar accepts (newer Mixin versions write annotations older loaders can't read).

## Where things are

Start with the `package-info.java` in each package: it says what the package does and which classes to read first.

| Path | What it is |
| --- | --- |
| `fabric/src/main/java/io/earthplatform/minecraft/fabric` | The Fabric entry points (`EarthPlatformFabric`, `EarthPlatformFabricClient`) and their mixins |
| `fabric/src/main/java/io/earthplatform/minecraft/platform` | What shared code asks of the loader |
| `.../minecraft/cubic` | The cubic world engine: cubes from Y -16384 to 16383, how they are made, saved, lit and sent (`ServerCubeWorld`, `CubeWorld`, `CubeStorage`, `CubicEvents`) |
| `.../minecraft/cubic/gen` | Terrain shared by every cubic world: `SurfaceGenerator` turns column heights into cubes; caves, rock layers, plants, trees and vanilla structures |
| `.../minecraft/cubic/earth` | The real Earth: `EarthGenerator` decides every column's height, water and surface from the Earth data |
| `.../minecraft/cubic/far` | Far terrain on the server: the voxel pyramid (`Far`) and the workers that make its columns (`FarServer`) |
| `.../minecraft/cubic/client` | Drawing: near cubes (`CubeRenderer`), far terrain (`FarRenderer`, `FarMesher` and `FarColumnMesh`, `FarGpu`, `FarShaders`) and shader pack support (`FarLod`, `PhotonCompat`) |
| `.../minecraft/cubic/net` | Cube and far column traffic, and the pacing for remote players |
| `.../minecraft/cubic/play` | What players and operators use: the Earth world type, settings, `/earth` commands, spawns, the travel map, games. Screens are in `play/client` |
| `.../minecraft/cubic/mixin` | Hooks into vanilla, Iris and Sodium, listed in `fabric/src/main/resources/earthplatform.cubic.mixins.json`; `mixin/compat` hooks into optional mods |
| `.../minecraft/cubic/compat`, `portal`, `jet` | Other mods, Nether and End travel, the jet stream |
| `geography/src/main/java/io/earthplatform/geography/globe` | The Earth data, with no Minecraft code: elevation tiles and national layers, land cover, climate, rivers, imagery, and their download and cache code |
| `fabric/src/test` | Unit tests, and `GlobeTest`, which runs the Earth data code against a local tile server. `CubicBench` (opt-in, set `EARTH_BENCH`) prints a world fingerprint for checking that a change leaves generation alone |
| [docs/FLAGS.md](FLAGS.md) | Every `-Dearthplatform.*` developer flag |
| [ROADMAP.md](../ROADMAP.md) | How the engine works and what comes next |

### Changing things safely

- A world pins its generator and data sources when it is made. Anything that changes generation needs a fresh world to see, and changes every world made after it.
- Seed salts, hash functions, saved ids, network payload ids, cache folder names, mixin class names and flag names are read by saved worlds, other clients or the mixin system. Keep them as they are unless you mean to break compatibility.
- Before and after a generation change, compare `CubicBench` fingerprints: `EARTH_BENCH=<cache folder> gradle test --tests '*CubicBench' --rerun -i`.

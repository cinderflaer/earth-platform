# Installing Earth Platform

## What you need

- Minecraft: Java Edition **26.2**, on Fabric only
- **Java 25**
- **Fabric Loader 0.20.1** or newer with **Fabric API** 0.161.0+26.2 or newer
- At least **4 GB** of memory for the game; at least **6 GB** if you use shaders such as Photon or raise the far terrain detail (on an M4 Pro Mac with Photon, 4 GB gave 8 to 18 fps where 6 GB gave 36 to 60)
- An internet connection the first time you visit each place
- A few GB of free disk space for downloaded Earth data (the limit is a setting)

## The quick way: an install pack

Each release comes with a pack file, `earth-platform-fabric-<build>.mrpack`. The pack sets up Minecraft 26.2, Fabric Loader, Fabric API, Earth Platform, Sodium, Iris and the Photon shader pack in one step, with Photon switched on.

1. Download the file from the [latest release](https://github.com/pirut/earth-platform/releases/latest).
2. Open it with a launcher that reads Modrinth packs: in the Modrinth App, **Add instance → From file**; in Prism Launcher, **Add Instance → Import** (or drag the file onto the window); in ATLauncher, **Import**.
3. Set the instance's memory to **6 GB** (a pack can't set this for you), then press Play. Singleplayer → Create New World: the world type is already **Earth**.

The pack holds the Earth Platform jar itself. Fabric API, Sodium, Iris and Photon are not copied into it: your launcher downloads each from its own Modrinth page. The rest of this page is the same setup by hand.

## Install

1. Run the [Fabric installer](https://fabricmc.net/use/installer/) for Minecraft 26.2 and choose **Client**, or make a Fabric 26.2 instance in Prism, the Modrinth App or MultiMC. Use Java 25 for the profile.
2. Open the Minecraft Launcher. Under **Installations**, edit the new Fabric profile, open **More options** and change `-Xmx2G` in **JVM arguments** to `-Xmx4G` (or more).
3. Start the profile once so it creates its folders, then quit.
4. Put [Fabric API](https://modrinth.com/mod/fabric-api) (0.161.0+26.2 or newer) and the Earth Platform jar (`earthplatform-fabric-<version>.jar` on the Releases page) into the `mods` folder of your game directory:
   - Windows: `%APPDATA%\.minecraft\mods`
   - macOS: `~/Library/Application Support/minecraft/mods`
   - Linux: `~/.minecraft/mods`
5. Start the game. Singleplayer → Create New World: the world type is already **Earth**. Press **Customize** to pick a starting place.

Other launchers (Prism, Modrinth App, CurseForge App, MultiMC): make a Fabric 26.2 instance on Java 25, add Fabric API and the jar, and set the memory to 4 GB or more (6 GB or more with shaders). Prism defaults to 4 GB, so raise it if you use Photon.

The settings screen is under Options → Video Settings → Earth Platform with Sodium, or in [Mod Menu](https://modrinth.com/mod/modmenu) if you have it.

## Shaders (optional)

Earth Platform is built and tested against Iris 1.11.4 and Sodium 0.9.2, the builds for Minecraft 26.2. Other versions of either may not work.

| Project | Version | Goes in |
| --- | --- | --- |
| [Sodium](https://modrinth.com/mod/sodium) | 0.9.2 for Fabric 26.2 | `mods` |
| [Iris](https://modrinth.com/mod/iris) | 1.11.4 for Fabric 26.2 | `mods` |
| [Photon](https://modrinth.com/shader/photon-shader/version/v1.3b) | v1.3b | `shaderpacks` (leave it zipped) |

Choose Photon under Options → Video Settings → Shader Packs. Press **K** to turn shaders off and on.

Packs that ship Voxy support (Photon does) light the far terrain the same way as nearby blocks. Other packs light near blocks normally, but far terrain may look flat or too bright. Suggested Photon settings for the Earth are in [Flying and far terrain](FLIGHT-RENDERING.md#recommended-photon-settings).

### On a Mac

Use the same official Photon zip at its default settings; there is nothing to change. Apple's OpenGL has no compute shaders and fewer texture slots than Photon's defaults need, so on a Mac Earth Platform makes an adapted copy of the pack for your own game in `earthplatform/photon-compat/` inside the game folder, and Iris loads that. Your Photon zip is never changed, and other systems run Photon exactly as it is.

On a Mac, three Photon features are unavailable: **Colored Lights** (hidden from the menu), **Colored Shadows** and **Cloud Shadows** (shown, but stay off). To load Photon without the adapted copy, add `-Dearthplatform.photon.compat=off` to the JVM arguments.

## Satellite ground colors (optional)

Earth Platform can use Sentinel-2 cloudless satellite imagery by EOX to match bare ground and plant cover more closely to each place. It is off by default. To turn it on for a new world, press **Customize** in Create World and set **Satellite imagery** to ON. To make it the default for every new world (and on servers), set `satelliteImagery = true` in `config/earthplatform-common.toml`, or use the Mod Menu config screen. Worlds made with imagery keep using it, and worlds made without it never use it.

The JVM argument `-Dearthplatform.earth.imagery=true` (or `=false`) also works and overrides the setting. Imagery adds downloads from `tiles.maps.eox.at`.

## Other mods

For other mods, see [Which other mods work?](FAQ.md#which-other-mods-work) in the FAQ.

## Dedicated servers

1. Run the [Fabric installer](https://fabricmc.net/use/installer/) for Minecraft 26.2 with **Server**, on Java 25.
2. Put Fabric API and the Earth Platform jar in the server's `mods` folder.
3. Before the world is first created, set `level-type=earthplatform\:earth` in `server.properties`.
4. Optionally set `defaultHome` in `config/earthplatform-common.toml` to a place id from `/earth places` or to `latitude,longitude`.
5. Once it runs, operators can move the spawn on a globe with `/earth spawn pick`, send players places and more; see [Server commands](PLAYING.md#server-commands).

The server downloads Earth data, so it needs internet access. Players need the same Earth Platform version; they do not need Iris, Sodium or a shader pack.

## Updating

Replace the jar with the new one. Ground you have already explored is saved and doesn't change under your builds. Ground you haven't visited yet is generated by the new version, and while Earth Platform is in alpha the generator still improves between releases, so you may see a seam where old and new ground meet. For the best result, start a fresh world after a big update.

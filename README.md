# Minecraft: Java Edition Extension

<p align="center">
  <a href="https://extensions.classiclauncher.net/?url=https://raw.githubusercontent.com/ClassicLauncher/classiclauncher-minecraft-java/main/manifest.yml">
    <img src="https://raw.githubusercontent.com/ClassicLauncher/ClassicLauncher/main/assets/install_extension.svg" alt="Install Extension"/>
  </a>
</p>

Minecraft: Java Edition game support for the ClassicLauncher.

Implements the full launch pipeline: version JSON resolution, library download with
SHA-1 verification, native extraction, asset download, and pre-launch token refresh.
Also provides a version API backed by Mojang's public version manifest and a tiled
background renderer for the launcher UI.

---

## Supported game

| Game                    | ID               | Launch                                                               |
|-------------------------|------------------|----------------------------------------------------------------------|
| Minecraft: Java Edition | `minecraft_java` | Full pipeline (version JSON -> libs -> natives -> assets -> process) |

---

## Architecture

```
MinecraftJavaExtension         LauncherExtension entry point
 ├─ MinecraftJava.GAME          Game descriptor (JAR, version filters, API + launch factories)
 │   ├─ MinecraftJavaVersionApi    Fetches Mojang manifest; supplements with local versions
 │   └─ MinecraftJavaLaunchStrategy  10-step prepare + buildCommand
 │       ├─ MinecraftFileDownloader  Atomic download, SHA-1 verify, CDN redirects
 │       ├─ MinecraftLibraryResolver Library rules, platform filtering, classpath + natives
 │       ├─ NativesExtractor         ZIP extraction to per-launch temp directory
 │       ├─ AssetDownloader          Asset index + objects, SHA-1 skip-if-valid
 │       ├─ LogConfigDownloader      Log4j config from version JSON logging section
 │       ├─ VersionJsonParser        Gson deserialiser for mixed-type argument arrays
 │       ├─ ArgumentEvaluator        Rule evaluation, ${variable} substitution
 │       └─ Sha1Verifier             SHA-1 hex comparison, null/missing-safe
 └─ TiledBackgroundRenderer     Tiled background with darkness + saturation (from microsoft-auth)
```

---

## Launch pipeline

```
MinecraftJavaLaunchStrategy.prepare()
  1.  Resolve version ID (profile override, or latest filtered by snapshot/beta/alpha flags)
  2.  Download version JSON from Mojang (or use cached copy)
  3.  Parse version JSON (VersionJsonParser + Gson)
  4.  Download + SHA-1-verify client JAR
  5.  Resolve + download + SHA-1-verify all libraries (platform rules applied)
  6.  Extract native JARs to per-launch temp directory
  7.  Download asset index + all referenced objects
  8.  Download Log4j log config referenced in version JSON
  9.  Ensure valid Minecraft access token (delegates to MinecraftAccount.ensureAccessToken())
  10. Log "All files ready. Launching..."

MinecraftJavaLaunchStrategy.buildCommand()
  ├─ Evaluate JVM arguments (modern rule-gated or legacy hardcoded)
  ├─ Prepend -XstartOnFirstThread on macOS
  ├─ Substitute all ${variable} tokens (auth, classpath, natives, assets, resolution, ...)
  └─ Return [javaExe, ...jvmArgs, mainClass, ...gameArgs]
```

**SHA-1 policy**: files are re-downloaded only when both a `sha1` and a `url` are present
in the version JSON. Locally-installed versions without a `url` are never re-downloaded,
preserving custom or patched installations.

---

## Version API: MinecraftJavaVersionApi

Fetches all available versions from Mojang's public manifest at
`https://launchermeta.mojang.com/mc/game/version_manifest_v2.json`.

Also scans the local `<gameDataDir>/versions/` directory and appends any locally-installed
versions not present in the remote manifest. Local versions require both a `<id>.json` and
`<id>.jar` file. Remote versions take precedence when IDs conflict.

| Manifest `type` | `VersionType.id` | `VersionType.name` |
|-----------------|------------------|--------------------|
| `release`       | `release`        | Release            |
| `snapshot`      | `snapshot`       | Snapshot           |
| `old_beta`      | `old_beta`       | Beta               |
| `old_alpha`     | `old_alpha`      | Alpha              |

---

## Required extensions

This extension requires the **Microsoft Account** extension, which provides:

- `MinecraftAccount` — the abstract auth contract used by the launch strategy
- `TiledBackgroundRenderer` — shared UI component for tiled backgrounds
- `MicrosoftAccountProvider` — the account provider this extension registers its game with

---

## Build

```bash
# Install the Launcher to your local Maven repo first (required — it is a provided dependency)
mvn -f ../Launcher/pom.xml install

# Install microsoft-auth to your local Maven repo (required — it is a provided dependency)
mvn -f ../MicrosoftAccountExtension/pom.xml install

# Build the extension JAR
mvn package
```

Output: `target/classiclauncher-minecraft-java-1.0-SNAPSHOT.jar`

Runtime dependencies (Gson, MinecraftAuth, lenni0451.commons) are **not** bundled.
They are declared by the required Microsoft Account extension and downloaded by the
Launcher's LibraryManager at extension install time.

---

## Install

1. Install the **Microsoft Account** extension first (it is a required dependency).
2. Host the built JAR and `manifest.yml` at a publicly accessible URL
   (or a local `file://` URL for development).
3. In the Launcher, open **Settings -> Extensions**, paste the manifest URL, and click **Install**.
4. Restart the Launcher. Minecraft: Java Edition appears as the default game.

---

## Code formatting

All code must be formatted before commit. This project uses
[Spotless](https://github.com/diffplug/spotless) with the Eclipse JDT formatter
(see `eclipse-formatter.xml`). IntelliJ IDEA picks up the committed `.idea/codeStyles/`
automatically.

```bash
# Check formatting
mvn spotless:check

# Apply formatting
mvn spotless:apply
```

It is recommended to install the pre-commit hook so formatting is applied automatically
on every `git commit`:

```bash
pip install pre-commit   # or: brew install pre-commit
pre-commit install
```

See [`Formatting.md`](Formatting.md) for the full style guide.

---

## Disclaimer

Minecraft, Minecraft: Java Edition, Mojang, and Microsoft are trademarks or registered trademarks of Microsoft Corporation and/or Mojang Studios. This project is not affiliated with, endorsed by, or sponsored by Microsoft Corporation or Mojang Studios. All trademarks are the property of their respective owners.

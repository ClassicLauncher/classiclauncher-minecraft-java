# Architecture Overview — Minecraft: Java Edition Extension

## Class diagram

```
LauncherExtension (interface, Launcher)
    └── MinecraftJavaExtension             <- entry point; discovered by JarClassFinder
                                             sets MinecraftJava.GAME as launcher default
                                             registers game with MicrosoftAccountProvider

Game (Launcher)
    └── MinecraftJava.GAME               <- apiFactory = MinecraftJavaVersionApi::new
                                            launchStrategyFactory = MinecraftJavaLaunchStrategy::new
                                            backgroundRendererFactory -> TiledBackgroundRenderer

GameApi (interface, Launcher)
    └── HttpGameApi (abstract, Launcher)
          └── MinecraftJavaVersionApi    <- fetches Mojang version manifest
                                           supplements with locally-discovered versions
                                           wired via MinecraftJava.GAME.apiFactory

LaunchStrategy (interface, Launcher)
    └── MinecraftJavaLaunchStrategy      <- full Minecraft Java launch pipeline
                                           registered via MinecraftJava.GAME launchStrategyFactory
                                           prepare(): version JSON, client JAR, libraries,
                                                      natives, assets, access token
                                           buildCommand(): JVM + game args with ${var} substitution

BackgroundRenderer (@FunctionalInterface, Launcher)
    └── TiledBackgroundRenderer          <- tiles image; configurable darkness + saturation
                                           (provided by microsoft-auth extension)
```

---

## Bootstrap sequence

```
Main.main()
  |
  +- Settings.load()
  |
  +- Extensions.loadAll()
  |     +- microsoft-auth extension loaded first (requiredExtensions ordering)
  |     |     +- MicrosoftExtension.onLoad()
  |     |           +- AccountType.register(MICROSOFT, ...)
  |     |           +- Accounts.onReady(callback A -> registerProvider)
  |     |
  |     +- classiclauncher-minecraft-java loaded second
  |           +- MinecraftJavaExtension.onLoad()
  |                 +- LauncherContext.setDefaultGame(MinecraftJava.GAME)
  |                 +- Accounts.onReady(callback B -> addGame to provider)
  |
  +- Accounts.signalReady(accounts)
        +- callback A: accounts.registerProvider(new MicrosoftAccountProvider())
        +- callback B: provider.addGame(MinecraftJava.GAME)

UI shown -> MinecraftJava.GAME is the launcher-wide default
```

Key points:

- The `requiredExtensions` declaration in `manifest.yml` ensures microsoft-auth is loaded
  first. Its `onReady` callback (A) fires before this extension's callback (B) because
  `Accounts.onReady` drains callbacks in FIFO order.
- `setDefaultGame` fires during `onLoad`, before `Accounts.signalReady`. This is safe ---
  `LauncherContext` is a singleton available from the first line of `Main`.
- `MinecraftJava.GAME` is constructed with `launchStrategyFactory = MinecraftJavaLaunchStrategy::new`.
  `GameLauncher` calls `game.createLaunchStrategy()` at launch time, which invokes this factory
  and returns a new instance.

---

## Launch pipeline: MinecraftJavaLaunchStrategy

### prepare --- 10-step pipeline

| Step | Action                                                                                         |
|------|------------------------------------------------------------------------------------------------|
| 1    | Resolve the version ID from the active profile                                                 |
| 2    | Download the version JSON from Mojang (or use cached copy from `<gameDataDir>/versions/<id>/`) |
| 3    | Parse the version JSON                                                                         |
| 4    | Download and SHA-1-verify the client JAR                                                       |
| 5    | Resolve, download, and SHA-1-verify all libraries declared in the version JSON                 |
| 6    | Extract native libraries to a per-launch temp directory                                        |
| 7    | Download and verify game assets (asset index + individual asset objects)                       |
| 8    | Download the Log4j log configuration file                                                      |
| 9    | Ensure a valid Minecraft access token via `MinecraftAccount.ensureAccessToken()`               |
| 10   | Log `"All files ready. Launching..."`                                                          |

**SHA-1 verification policy:** Files are re-downloaded only when **both** `sha1` and `url` are
present in the version JSON entry. Locally-installed versions without a `url` field are never
re-downloaded, even on a checksum mismatch --- this preserves custom or manually-patched
installations.

### buildCommand --- variable substitution

`buildCommand` assembles the full JVM + game argument list by substituting `${variable}` tokens.
Both the legacy `minecraftArguments` string (pre-1.13) and the modern split
`arguments.jvm` + `arguments.game` arrays are supported. On macOS, `-XstartOnFirstThread` is
prepended automatically.

Key tokens include `${auth_player_name}`, `${auth_uuid}`, `${auth_access_token}`, `${classpath}`,
`${natives_directory}`, `${game_directory}`, `${assets_root}`, and `${version_name}`.

---

## Version API: MinecraftJavaVersionApi

`MinecraftJava.GAME` has `apiFactory = MinecraftJavaVersionApi::new`. When the Profile Editor
opens, it calls `game.createApi()` which constructs a new instance.

```
ProfileEditorDialog opens
  +- game.createApi()
        +- new MinecraftJavaVersionApi()   <- extends HttpGameApi
              +- getAvailableVersions()
                    +- fetchText("/mc/game/version_manifest_v2.json")
                    |     from https://launchermeta.mojang.com
                    +- hand-rolled JSON parser (no Gson needed)
                    +- scan <gameDataDir>/versions/<id>/   <- locally-discovered versions
                    |     validates: version JSON present + client JAR present
                    |     logs debug messages for missing/invalid entries
                    |     remote versions take precedence when IDs conflict
                    +- List<Version> (cached after first fetch)
```

---

## Separation of concerns

This extension depends on the Microsoft Account extension for:

- `MinecraftAccount` --- abstract base class defining the Minecraft auth contract
  (`getMinecraftUsername()`, `getMinecraftUUID()`, `ensureAccessToken()`, etc.)
- `TiledBackgroundRenderer` --- shared UI component for tiled backgrounds

The launch strategy only references `MinecraftAccount` (the abstract base), never
`MicrosoftAccount` or `MicrosoftAuthService` directly. Token refresh is delegated
to `MinecraftAccount.ensureAccessToken()`, which each account implementation overrides
with its own refresh logic.
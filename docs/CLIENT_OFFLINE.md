# Real client, offline, no Microsoft account (dev only)

Purpose: drive the official Minecraft Java 26.3 client against the local dev server for QA. No account, launcher or token is used.

## Safety rules (enforced in the scripts)
- Server bound to loopback only (`server-ip=127.0.0.1`), `online-mode=false`; the scripts refuse non-loopback addresses. These `dev/server` edits are dev-only, are not in git, and are not part of any shipped artifact. The EULA is never written by the scripts (`Assert-Eula`).
- Downloads come only from Mojang's official hosts (`piston-meta`, `piston-data`, `libraries.minecraft.net`, `resources.download.minecraft.net`), every file is SHA-1 verified against the manifest, into a **fresh empty directory outside the repo** (default `D:\test\mcmmo-client-qa`). Treat it as untrusted data; never copy it into the repository.
- Input (`SendInput`) is sent only while the Minecraft window is the foreground window (checked before every key/click; the helper aborts otherwise). Screenshots capture only the Minecraft client rectangle.

## Steps
1. `./scripts/client/get-client.ps1 -Root D:\test\mcmmo-client-qa` (one time, ~490 MB: client jar, 74 libraries, 5149 assets).
2. Dev server: put the QA player in `ops.json` (offline UUID = UUIDv3 of `OfflinePlayer:QaBot`) and set `server-ip=127.0.0.1`.
   `pwsh ./scripts/client/server-host.ps1` starts Paper in the background. Append lines to `D:\test\mcmmo\dev\qa-console.txt` to send console commands, e.g. `ec world QaBot`, `ec spawn ec:enemy_thorn_wolf QaBot`, `execute in minecraft:ec_world run tp QaBot 90 66 0`, `stop`. (A plain `tp` from the console moves the player in the vanilla overworld, not in ec_world.)
3. `./scripts/client/launch-client.ps1 -Name QaBot` starts the client with `--username QaBot --uuid <offline uuid> --accessToken 0 --userType legacy --quickPlayMultiplayer 127.0.0.1:25565`. The first run shows a "Welcome / Narrator" screen that must be confirmed once (Continue) before quick-play connects. The `java.exe` that is launched is a shim; the real game is its child process (`Get-CimInstance Win32_Process`, command line contains `net.minecraft.client`).
4. Window control (`scripts/client/mcwin.ps1`): `Pin-Mc` (always-on-top so screenshots work), `Focus-Mc` (zero-delta mouse move + AttachThreadInput; needed because the desktop's foreground-lock timeout blocks plain SetForegroundWindow), `Save-McShot`, `Press-McKey`, `Click-McAt`, `Type-McChat`. The host application steals the foreground every few seconds, so each action burst must call `Focus-Mc` immediately before and finish within about a second.
5. Stop: kill the game pid (and its shim), send `stop` to the server console file, remove QaBot from `ops.json`, restore `server.properties` (this run restored `server-ip=` to empty; `online-mode=false` was already the dev default).

## Limits found
- Mouse look works with relative `SendInput` moves; keyboard works through virtual keys. Chat typing via `T` is ASCII only.
- An unfocused Minecraft pauses itself in-game; do not leave it unfocused during timed tests.
- Realms/auth errors in the client log (`401`, `SignedJWT`) are expected without an account and harmless.

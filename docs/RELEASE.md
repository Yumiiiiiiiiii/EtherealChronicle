# RELEASE

## v0.1.0 release candidate

Build with Java 25 using `./gradlew.bat test build`; deploy `build/libs/EtherealChronicle-0.1.0.jar` to Paper 26.3 build 257. The operator must accept the Minecraft EULA (`eula=true`) and configure `online-mode=true` for public servers. No push or release deployment is performed automatically.

### Bundled artifacts

- **Plugin JAR**: `build/libs/EtherealChronicle-0.1.0.jar` (shades and relocates `InvUI 2.5.1` to `net.nakaymi.etherealchronicle.libs.invui`; license bundled at `META-INF/licenses/InvUI-LICENSE.txt`, see `docs/THIRD_PARTY_NOTICES.md`).
- **Resource pack**: reproducible ZIP bundled inside the JAR (`resourcepack/etherealchronicle-resourcepack.zip`, pack format `[97, 1]`) and emitted to `build/generated/resourcepack/etherealchronicle-resourcepack.zip` + `.sha1`. Inspect the bundled hash and byte size in-game or from console with `/ec pack info`.
- **Core datapack**: `datapacks/etherealchronicle_core` (pack format `[121, 0]`, custom wolf variants and biomes) registered automatically at startup via `EtherealBootstrap` (`LifecycleEvents.DATAPACK_DISCOVERY`) without unpacking into the world folder.
- **Schematics & content**: 27 Overworld schematics, 4 dungeon arena schematics, 457 indexed YAML content files, and `lang/ja_jp.yml` + `lang/en_us.yml`.

### Implemented scope & world modes

Implemented and connected: class selection/starter/HUD/skills/equipment, Chapter 1 & Chapter 2 quest objectives/branches/rewards, NPC dialogue and shops (with interaction distance guard), consumables, currency/token trades, fishing/gathering/treasure chests, custom spawn points/AI/virtual bosses/telegraphs, parties/shared rewards, and cooperative dungeons. Pity applies only to authored rare entries with per-profile, per-item counters; bespoke lore-specific boss scripts are not implemented, and attacks use the documented generic shapes/traits. The REVIVE feather is an accessory that prevents one lethal hit while equipped; using it by right-click shows an equip instruction and preserves the item.

World mode is set via `config.yml`:

- `world.mode: overworld` (only mode): towns and dungeon portals are integrated into the vanilla Overworld. The legacy `dedicated_ec_world` (floating-island) value was removed and is read as `overworld`.

### Transactions, persistence & crash caveats

Normal disconnect/disable purchase outputs are preserved in `pending_rewards` and can be claimed after rejoining. Full inventories retain rewards, without ground drops. Transactions lock against repeated clicks, retain recent transaction IDs, and roll back synchronous failures. Buying saves the debit/output entitlement before granting items. Selling/token consumption saves Paper inventory after removal and before payout; this is a deliberate small main-thread IO exception.

Paper inventories and plugin profiles remain independent files. Abrupt crash/power loss during `saveData` itself or between durable claim removal and physical item persistence is not formally atomic. The ordering favors possible loss; it is not a proof against every storage/crash anomaly. Starter entitlement has a similar at-most-once grant window. Potions use normal Paper inventory persistence rather than forcing a disk write per drink. Keep synchronized backups of `<world>/playerdata/`, `plugins/EtherealChronicle/playerdata/`, `plugins/EtherealChronicle/content/`, and `<overworld>/.etherealchronicle-overworld.json`; do not advertise exactly-once cross-store behavior.

### Admin & recovery commands

Requires `etherealchronicle.admin` (default: `op`; declared in both `paper-plugin.yml` and `plugin.yml`).

- **Overworld operations**: `/ec sites`, `/ec sites repair`, `/ec sites acknowledge-cleanup <areaId>`, `/ec dungeon release <slot>`, `/ec travel <areaId>`.
- **Content & pack diagnostics**: `/ec validate`, `/ec reload`, `/ec pack info`.
- **Player compensation**: check saved `pending_rewards` and have the player run `/ec claim` first to avoid double recovery. Then use `/ec starter <player>` for a lost starter kit, `/ec give <player> <item-id> [amount]` for confirmed lost output, and `/ec currency <player> <absolute-balance>` for balance correction. Never repeat compensation without checking the existing entitlement.

Content upgrades preserve server edits. Removed unchanged foundation files are quarantined under `retired-content`; invalid content logs file/ID and uses bundled fallback while leaving `/ec validate` and `/ec reload` usable. Older active defaults may still require manual reconciliation. Back up `content/` and `playerdata/` before upgrading.

### Resource-pack delivery & BetterModel

Resource-pack delivery defaults to the experimental `game_port` mode with `same_port_netty.enabled: false` and empty `external_url`; no same-port listener is installed unless both opt-ins are set, and on an unconfigured boot required-pack enforcement is automatically relaxed with a warning so players are never locked out. Same-port delivery uses Paper's internal `ChannelInitializeListenerHolder` through reflection and may break on a Paper update; the handler only diverts bounded `GET`/`HEAD` requests for the fixed pack hash path and passes ordinary Minecraft bytes through.

For production servers, upload `build/generated/resourcepack/etherealchronicle-resourcepack.zip` to HTTPS hosting and configure either `mode: external_url` (`external_url` + matching 40-char `external_sha1`) or `mode: external_managed` (with ForcePack/ResourcePackManager). Set a server MOTD reminding players to enable server resource packs. Operators and QA accounts can use `etherealchronicle.pack.bypass` (default: `op`). BetterModel is an optional soft dependency (`load: BEFORE`, `required: false`); its model API is called reflectively and falls back to the configured wolf variant when absent or incompatible. See `docs/RESOURCEPACK_HOSTING.md`.

### Damage adapter review

Paper 26.3 still marks `EntityDamageEvent.DamageModifier` deprecated and describes the API as unsustainable; the API docs do not name a drop-in replacement. The current handler sets the RPG base hit and zeroes applicable armor/resistance/blocking modifiers while retaining absorption. Replacing it with only `event.damage = amount` would let vanilla reductions apply on top of RPG defense and change combat balance; directly mutating health would bypass the damage event/absorption semantics. Keep the adapter for this Paper target and retest after Paper upgrades. See [Paper 26.3 EntityDamageEvent](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityDamageEvent.html) and [DamageModifier deprecation](https://jd.papermc.io/paper/26.3/org/bukkit/event/entity/EntityDamageEvent.DamageModifier.html).

### Verification status

- **Automated suite**: 419 JUnit/MockBukkit tests PASS (0 failures, 0 errors; 3 pre-existing skips due to MockBukkit unimplemented world-spawn/block-data-rotation APIs), including resource-pack asset integrity and overworld schematic/placement tests.
- **Live Paper 26.3 QA (`docs/MANUAL_QA_RESULT.md`)**:
  - Dedicated & Overworld boot, reload, profile save/restart, upgrade fallback, simulation combat/lethal rewards, and offline real-client gameplay checks (class selection, portal walk-in/leave, gathering, fishing, treasure chests, NPC dialogue/shop, custom pack rendering).
  - Overworld regression QA #12–#13: **7/7 consecutive PASS** (2 repro seeds + 5 fresh random seeds), 9/9 `READY`, 4 portals, 0 ERROR, 0 house/pool/projection WARN, TPS 19.6–19.9, with `world.save(true)` before every `READY` journal commit.
- **Remaining manual/unverified scenarios**: a full end-to-end real-client Chapter 1 + Chapter 2 playthrough, edge vanilla interactions (number-key bound transfer, frame/stand/allay/bundle/crafting), and hard kill-9/power-loss recovery mid-write.

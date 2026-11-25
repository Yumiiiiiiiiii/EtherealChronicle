# Review: refactor/remove-islands

## High

- **Med — Bug — `src/main/kotlin/net/nakaymi/etherealchronicle/paper/AdventureController.kt:403`**: Death handling zeroes `profile.currency` and starts `plugin.save(profile)` directly, outside `AdventureController`'s per-player transaction lock and `persist` completion/rollback path. If a transaction is already persisting a snapshot, it can overwrite the death penalty; if its callback rolls back a prior snapshot, it can restore the currency after it was set to zero. Since `save` is asynchronous and this handler doesn't serialize with pending reward claims/transactions, the outcome depends on save ordering. Queue the penalty through `profileTransaction`/the lock and apply it to the transaction's current profile state before durable persistence; report the loss only after successful commit.

- **Low — Test — `src/main/kotlin/net/nakaymi/etherealchronicle/paper/RpgPlugin.kt:93-95`**: Production `RpgPlugin` references `TestWorldFixture`, whose setter is in main source and can be set by any same-module runtime code; fixture selection is gated only by absence of `world.mode`. This means a test seam is shipped and can redirect a real server into the fixed-site registry when the config omits the key. Move the seam behind a test-only subclass/factory or inject the registry in tests without a mutable production global.

## Other requested checks

- No finding: `WorldMode.fromConfig` now maps `dedicated_ec_world` to `OVERWORLD` and defaults a missing value to `OVERWORLD`; the mapping is explicit and consistent with the removed mode.
- No finding: `RpgPlugin.playerDeath` still removes tagged skill-slot drops, which remains compatible with `AdventureController.death` clearing normal drops and preserving inventory.
- No finding: dungeon death retains the existing `DungeonController.respawn` route for members in an active instance; the reviewed change does not remove that behavior.
- No finding: quest reward weapons removed from `m01_awakening.yml` are present as `starter_items` for all six classes: archer, assassin, cleric, mage, paladin, and warrior. No class loses its only weapon path due to this diff.

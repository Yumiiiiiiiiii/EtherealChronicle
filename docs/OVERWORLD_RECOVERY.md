# Overworld generation recovery

The overworld layout journal (`<overworld>/.etherealchronicle-overworld.json`) records site state, coordinates, and anchors. It does not contain a complete before-and-after snapshot of every changed block.

## Routine inspection

- Use `/ec sites` to inspect all 9 overworld sites (`PENDING` / `GENERATING` / `READY` / `FAILED`), resolved coordinates, biomes, and the 4 dungeon entrance/exit portals.
- On a fresh `world.mode: overworld` boot, wait for all 9 sites to reach `READY` (typically ~3–4 minutes in Paper 26.3 QA runs) before opening the server to players.
- Administrators with `etherealchronicle.admin` can inspect a site directly with `/ec travel <areaId>` (short names such as `lumina_port` or `glacial_citadel` are accepted).

## Recovering `GENERATING` or `FAILED` sites

If generation stops while a site is `GENERATING` or `FAILED`, startup and `/ec sites repair` (`repair()`) do not rebuild that site automatically.

1. Inspect the partial site in-world (e.g., `/ec sites`, `/ec travel <areaId>`) and restore or remove the partial construction manually.
2. Confirm completion of the manual cleanup with `/ec sites acknowledge-cleanup <areaId>` (calls `acknowledgeCleanup(siteId)`). This changes the site status back to `PENDING` and clears its saved anchors and portals. It updates journal state only and does not modify world blocks.
3. Run `/ec sites repair` (calls `repair()`) to resume placement for `PENDING` sites.

Persisting a full per-block operation journal and safely resuming partially applied construction are future work. Do not treat the current journal as a world backup or an automatic rollback mechanism.

## Recovering `PENDING` planning failures

If planning fails while a site is still `PENDING`, inspect the logged final candidate error and the site's terrain or obstructing blocks before retrying. Fix the terrain or configuration, then run `/ec sites repair`; no `acknowledge-cleanup` acknowledgement is needed because placement did not start. Do not delete or reset `.etherealchronicle-overworld.json` to force a retry.

## Recovering lingering sky-dungeon instance slots

In Overworld mode, dungeon runs are placed in sky arena slots (default `world.dungeon.base_x: 100000`, `base_y: 220`, `base_z: 100000`, `spacing: 256`, slots `0..15`). If an abnormal shutdown leaves a protected, non-active dungeon slot behind:

- Run `/ec dungeon release <slot>` (requires `etherealchronicle.admin`, `<slot>` in `0..15`, tab-completed from releasable slots) to clean up and release the slot. Active runs cannot be released while players are inside.

## Durability and spawn semantics

Before marking a site `READY` in `.etherealchronicle-overworld.json`, `OverworldWorldService.finishSite` explicitly calls `world.save(true)` to flush loaded chunks and level data to disk. If that flush fails, the site is not marked `READY` and remains `GENERATING`. However, Paper's world save and the JSON journal write remain two separate non-atomic operations; a hard crash or power loss mid-write still requires a future recovery protocol to be strictly crash-atomic.

When Lumina finishes placement, the service verifies a solid, non-hazardous floor and two air blocks within its plaza, flushes world chunks via `world.save(true)`, and saves `anchors["spawn"]` with the `READY` journal entry, then changes the shared Overworld spawn to that location, facing south (yaw 0), before notifying `onSiteReady`. If the spawn setter rejects the location or the cell becomes unsafe during the save, the service attempts to save `FAILED`; if no safe plaza cell exists, the journal remains `GENERATING`. This is a server-wide spawn change, not just an EtherealChronicle player setting; the controller integration separately handles player-specific spawning and should not set the world spawn again. A crash between `READY` persistence and the world spawn change remains a separate consistency gap.

## Verification status

The bundled 27 overworld schematics are parsed and their palette text checked by unit tests. Real Paper 26.3 smoke runs (`docs/MANUAL_QA_RESULT.md`, QA #12–#13) have verified 9/9 site placement, 4 dungeon portals, restart reload, and TPS stability (19.6–19.9) across 7 consecutive repro and random seeds, while hard crash-kill recovery during an in-flight write remains a manual recovery scenario.

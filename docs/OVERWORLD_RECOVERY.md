# Overworld generation recovery

The overworld layout journal records site state and anchors. It does not contain a complete before-and-after snapshot of every changed block.

If generation stops while a site is `GENERATING` or `FAILED`, startup and `repair()` do not rebuild that site. An administrator must inspect the site and restore or remove the partial construction manually. Only after that manual cleanup should the integration call `acknowledgeCleanup(siteId)`; this changes the site to `PENDING` and clears its saved anchors and portals. Calling `repair()` can then resume pending sites. The cleanup confirmation changes journal state only and does not modify the world.

Persisting a full per-block operation journal and safely resuming partially applied construction are future work. Do not treat the current journal as a world backup or an automatic rollback mechanism.

Before marking a site `READY` in `.etherealchronicle-overworld.json`, `OverworldWorldService.finishSite` explicitly calls `world.save(true)` to flush loaded chunks and level data to disk. If that flush fails, the site is not marked `READY` and remains `GENERATING`. However, Paper's world save and the JSON journal write remain two separate non-atomic operations; a hard crash or power loss mid-write still requires a future recovery protocol to be strictly crash-atomic.

When Lumina finishes placement, the service verifies a solid, non-hazardous floor and two air blocks within its plaza, flushes world chunks via `world.save(true)`, and saves `anchors["spawn"]` with the `READY` journal entry, then changes the shared Overworld spawn to that location, facing south (yaw 0), before notifying `onSiteReady`. If the spawn setter rejects the location or the cell becomes unsafe during the save, the service attempts to save `FAILED`; if no safe plaza cell exists, the journal remains `GENERATING`. This is a server-wide spawn change, not just an EtherealChronicle player setting; the controller integration separately handles player-specific spawning and should not set the world spawn again. A crash between `READY` persistence and the world spawn change remains a separate consistency gap.

If planning fails while a site is still `PENDING`, inspect the logged final candidate error and the site's terrain or obstructing blocks before retrying. Fix the terrain or configuration, then call `repair()`; no cleanup acknowledgement is needed because placement did not start. Do not delete or reset a journal to force a retry.

The bundled 27 overworld schematics are parsed and their palette text checked by unit tests. Real Paper 26.3 smoke runs (`docs/MANUAL_QA_RESULT.md`) have verified 9/9 site placement, portal construction, and restart reload across multiple random seeds, while hard crash-kill recovery during an in-flight write remains a manual recovery scenario.

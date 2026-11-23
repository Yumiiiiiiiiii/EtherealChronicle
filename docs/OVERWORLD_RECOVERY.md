# Overworld generation recovery

The overworld layout journal records site state and anchors. It does not contain a complete before-and-after snapshot of every changed block.

If generation stops while a site is `GENERATING` or `FAILED`, startup and `repair()` do not rebuild that site. An administrator must inspect the site and restore or remove the partial construction manually. Only after that manual cleanup should the integration call `acknowledgeCleanup(siteId)`; this changes the site to `PENDING` and clears its saved anchors and portals. Calling `repair()` can then resume pending sites. The cleanup confirmation changes journal state only and does not modify the world.

Persisting a full per-block operation journal and safely resuming partially applied construction are future work. Do not treat the current journal as a world backup or an automatic rollback mechanism.

Paper's chunk saves and the JSON journal are separate writes. After a crash, the journal may say `COMPLETED` even if recent chunk changes were not saved. This crash-consistency gap remains unresolved and requires real Paper crash/restart testing and a future recovery design.

When Lumina finishes placement, the service verifies a solid, non-hazardous floor and two air blocks within its plaza. It saves `anchors["spawn"]` with the `READY` journal entry, then changes the shared Overworld spawn to that location, facing south (yaw 0), before notifying `onSiteReady`. If the spawn setter rejects the location or the cell becomes unsafe during the save, the service attempts to save `FAILED`; if no safe plaza cell exists, the journal remains `GENERATING`. This is a server-wide spawn change, not just an EtherealChronicle player setting; the controller integration separately handles player-specific spawning and should not set the world spawn again. A crash between `READY` persistence and the world spawn change remains a separate consistency gap.

If planning fails while a site is still `PENDING`, inspect the logged final candidate error and the site's terrain or obstructing blocks before retrying. Fix the terrain or configuration, then call `repair()`; no cleanup acknowledgement is needed because placement did not start. Do not delete or reset a journal to force a retry.

The bundled 27 overworld schematics are parsed and their palette text checked by unit tests. MockBukkit tests cover a successful non-town plan without a pool and a failed town plan without world mutation; they do not establish successful placement of a complete town. Successful placement, spawn persistence across restart, and crash recovery have not been verified on a real Paper server. A future mitigation could explicitly save loaded chunks before marking a site `READY`, but the chunk and JSON writes would still need a recovery protocol to be crash-atomic.

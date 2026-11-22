# Overworld generation recovery

The overworld layout journal records site state and anchors. It does not contain a complete before-and-after snapshot of every changed block.

If generation stops while a site is `GENERATING` or `FAILED`, startup and `repair()` do not rebuild that site. An administrator must inspect the site and restore or remove the partial construction manually. Only after that manual cleanup should the integration call `acknowledgeCleanup(siteId)`; this changes the site to `PENDING` and clears its saved anchors and portals. Calling `repair()` can then resume pending sites. The cleanup confirmation changes journal state only and does not modify the world.

Persisting a full per-block operation journal and safely resuming partially applied construction are future work. Do not treat the current journal as a world backup or an automatic rollback mechanism.

Paper's chunk saves and the JSON journal are separate writes. After a crash, the journal may say `COMPLETED` even if recent chunk changes were not saved. This crash-consistency gap remains unresolved and requires real Paper crash/restart testing and a future recovery design.

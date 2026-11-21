# RELEASE

## Chapter 1 candidate

Build with Java 25 using `./gradlew.bat build test`; deploy `build/libs/EtherealChronicle-<version>.jar` to Paper 26.3 build 257. The operator must accept the Minecraft EULA. No push or release deployment is performed by the implementation task.

Implemented and connected: class selection/starter/HUD/skills/equipment, Chapter 1 quest objectives/branches/rewards, NPC dialogue and shops, consumables, currency/token trades, custom spawn points/AI/virtual bosses/telegraphs, parties/shared rewards, and the cooperative sanctum in the owned demo world. The world is a functional floating-island demo with bounded structures, not finished environmental art. Pity drops and bespoke lore-specific boss scripts are not implemented; attacks use the documented generic shapes/traits. No real-client full chapter playthrough has been performed.

Normal disconnect/disable purchase outputs are preserved in pending_rewards and can be claimed after rejoining. Full inventories retain rewards, without ground drops. Transactions lock against repeated clicks, retain recent transaction IDs, and roll back synchronous failures. Buying saves the debit/output entitlement before granting items. Selling/token consumption saves Paper inventory after removal and before payout; this is a deliberate small main-thread IO exception.

Paper inventories and plugin profiles remain independent files. Abrupt crash/power loss during saveData itself or between durable claim removal and physical item persistence is not formally atomic. The ordering favors possible loss; it is not a proof against every storage/crash anomaly. Starter entitlement has a similar at-most-once grant window. Potions use normal Paper inventory persistence rather than force a disk write per drink. Keep backups and logs; do not advertise exactly-once cross-store behavior.

Admin recovery: use `/ec starter <player>` for a lost starter kit, `/ec give <player> <item-id> [amount]` for confirmed lost output, and `/ec currency <player> <absolute-balance>` for balance correction. Check saved pending_rewards and use `/ec claim` first to avoid double recovery. The recovery commands require the administrative permission; never repeat compensation without checking the existing entitlement.

Content upgrades preserve server edits. Removed unchanged foundation files are quarantined under retired-content; invalid content logs file/ID and uses bundled fallback while leaving validate/reload usable. Older active defaults may still require manual reconciliation. Back up content and playerdata before upgrading. Do not remove ownership markers from an active ec_world; unknown existing worlds are refused rather than altered.

Verification: core/content/persistence JUnit and MockBukkit scenarios are automated. Independent QA verified earlier candidate live boot, reload, profile save/restart, upgrade fallback and startup reply. A compatible Minecraft bot/client was unavailable, so number-key bound transfer, frame/stand/allay/bundle/crafting interactions, actual combat timing/telegraphs and full dungeon movement remain unverified live. The deprecated Paper damage-modifier adapter is a compatibility risk to retest on Paper upgrades.

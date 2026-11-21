# Console simulation

All commands require `etherealchronicle.admin` (console/op allowed). Format: `ec debug sim <operation> <name> [arguments]`. Names map to deterministic fake UUIDs; simulation profiles are isolated under `plugins/EtherealChronicle/simulation/`. These commands mutate test state and may spawn actual RPG mobs only in the plugin-owned ec_world.

Operations:

- `join <name>` creates a new in-memory fake profile; `class <name> ec:warrior|ec:archer|ec:mage` selects the canonical class and virtual starter kit.
- `dump <name>` prints the profile; `inventory <name>` prints the virtual bag. `save <name>` asynchronously persists the profile; `load <name>` replaces it from disk and explicitly resets the virtual bag/conversation. Bags, conversation and parties do not persist across restart.
- `give <name> <item-id> [amount]` adds 1..64 virtual items. `currency <name> <absolute-balance>`, `level <name> <level>` and `flag <name> <flag-id> [clear]` set bounded test state. Flags are an administrative test override.
- `accept <name> <quest-id>`, `event <name> TALK|KILL|COLLECT|EXPLORE <target-id> [amount]`, and `complete <name> <quest-id>` use the real quest engine. `quest` is an alias for complete. COLLECT checks the virtual bag; add the item with give first. Sequential objectives only advance in order.
- `dialogue <name> <dialogue-id> [node-id]` prints the resolved node/text/available choices. `choice <name> <choice-id>` validates and executes it, applying flags before rewards. Failed multi-action choices roll back profile and bag. OPEN_SHOP/ENTER_DUNGEON report their intended effect; no screen or player teleport occurs.
- `buy <name> <shop-id> <entry-id> [transaction-id]` and `sell <name> <shop-id> <item-id> <amount> [transaction-id]` use real trade plans, conditions, caps, token costs, daily limits and replay rejection. The virtual inventory is memory-only; these are not physical inventory durability tests.
- `party <name> invite <other-name>`, `party <name> accept`, `party <name> leave`, `party <name> split <xp>` and `party <name>` exercise the shared party service. split prints shares without awarding them.
- `skill <name> [skill-id]` uses real cost/cooldown/GCD logic. `kill <name> <enemy-id>` grants real profile XP/currency/quest credit and rolls drops into the virtual bag; it does not simulate damage.
- `spawn <name> <enemy-id>` creates an actual tracked mob at its first configured point (or world spawn), printing its UUID and virtual HP. Point/global caps apply. Console targets disable AI and hold a plugin chunk ticket so they remain reachable without players. `hit <name> <entity-UUID> <damage>` applies bounded virtual damage through the actual event seam, reporting HP/phase/death and calling GameService.kill once for the fake profile on death. Fake attackers never receive real Player inventory rewards. `despawn <name>` removes this profile's console targets and releases chunk tickets; death, reload and shutdown also release them. Real AI, BossBar and particles still require a client to observe.

Example fresh-profile onboarding and trade check (commands entered one at a time):

```text
ec debug sim join qa_ch1
ec debug sim class qa_ch1 ec:warrior
ec debug sim dialogue qa_ch1 ec:dlg_celia_hub
ec debug sim choice qa_ch1 register_existing_adventurer
ec debug sim event qa_ch1 TALK ec:npc_celia
ec debug sim event qa_ch1 TALK ec:npc_vulcan
ec debug sim event qa_ch1 TALK ec:npc_garudo
ec debug sim event qa_ch1 TALK ec:npc_vulcan
ec debug sim complete qa_ch1 ec:quest_m01_awakening
ec debug sim currency qa_ch1 1000
ec debug sim buy qa_ch1 ec:shop_lumina_general minor_hp_potion_convenience qa_buy_1
ec debug sim buy qa_ch1 ec:shop_lumina_general minor_hp_potion_convenience qa_buy_1
ec debug sim inventory qa_ch1
ec debug sim save qa_ch1
```

The second qa_buy_1 must fail without another debit or item. Wait for the explicit SIM save success before testing restart/load. MockBukkit mailbox tests cover physical delivery/quit paths separately; console simulation does not replace that coverage or a real-client playthrough.

# Architecture
See AGENTS.md for rules. Layers: data (YAML load/validate/migrate) -> core (pure logic) -> paper (Bukkit glue). ContentRegistry<T> per type, atomic reload via `/ec reload`. PlayerProfile store with in-memory cache, async save. Event bus not needed; listeners call core services.

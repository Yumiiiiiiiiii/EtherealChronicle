package net.nakaymi.etherealchronicle.paper

import org.bukkit.entity.Entity
import org.bukkit.plugin.java.JavaPlugin
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** BetterModelが導入された環境だけで、設定済み魔物モデルを任意適用します。 */
class BetterModelBridge(private val plugin: JavaPlugin) : AutoCloseable {
    private val enabled = plugin.config.getBoolean("bettermodel.enabled", false)
    private val models = configuredModels(plugin.config.getConfigurationSection("bettermodel.entity_models")?.getValues(false).orEmpty())
    private val trackers = ConcurrentHashMap<UUID, Any>()
    private val warned = ConcurrentHashMap.newKeySet<String>()
    private val modelPlugin = plugin.server.pluginManager.getPlugin("BetterModel")
    private val apiLoader = modelPlugin?.javaClass?.classLoader ?: plugin.javaClass.classLoader

    init {
        if (enabled && models.isNotEmpty() && modelPlugin?.isEnabled != true) {
            plugin.logger.info("BetterModel is optional and was not found; vanilla mob visuals remain active.")
        }
    }

    /** 設定されたモデルだけを紐付け、API不在やモデル欠落時はバニラ見た目を保ちます。 */
    fun attach(entity: Entity, contentId: String) {
        if (!enabled) return
        val modelId = models[contentId] ?: return
        if (modelPlugin?.isEnabled != true) return

        runCatching { createTracker(entity, modelId) }
            .onSuccess { tracker ->
                if (tracker == null) warnOnce(modelId, "BetterModel model '$modelId' is not registered; keeping the configured vanilla variant.")
                else trackers.put(entity.uniqueId, tracker)?.let(::closeTracker)
            }
            .onFailure { error ->
                warnOnce(modelId, "Could not attach BetterModel '$modelId'; keeping the configured vanilla variant: ${error.message}")
            }
    }

    /** エンティティ破棄時にBetterModelのtrackerを閉じます。 */
    fun detach(entityId: UUID) {
        trackers.remove(entityId)?.let(::closeTracker)
    }

    /** プラグイン停止時に登録済みモデルtrackerを解放します。 */
    override fun close() {
        trackers.values.toList().forEach(::closeTracker)
        trackers.clear()
    }

    private fun createTracker(entity: Entity, modelId: String): Any? {
        val api = apiLoader.loadClass("kr.toxicity.model.api.BetterModel")
        val model = api.getMethod("model", String::class.java).invoke(null, modelId) as? java.util.Optional<*> ?: return null
        val renderer = model.orElse(null) ?: return null
        val adapterType = apiLoader.loadClass("kr.toxicity.model.api.bukkit.platform.BukkitAdapter")
        val platformEntity = adapterType.getMethod("adapt", Entity::class.java).invoke(null, entity)
        val create = renderer.javaClass.methods.firstOrNull { method ->
            method.name == "getOrCreate" && method.parameterCount == 1 && method.parameterTypes[0].isInstance(platformEntity)
        } ?: error("BetterModel renderer does not expose getOrCreate(PlatformEntity)")
        return create.invoke(renderer, platformEntity)
    }

    private fun closeTracker(tracker: Any) {
        runCatching { tracker.javaClass.getMethod("close").invoke(tracker) }
            .onFailure { plugin.logger.fine("Could not close BetterModel tracker: ${it.message}") }
    }

    private fun warnOnce(id: String, message: String) {
        if (warned.add(id)) plugin.logger.warning(message)
    }

    companion object {
        /** YAMLの任意マップから許可形式だけを取り出し、不正キーを適用対象にしません。 */
        internal fun configuredModels(values: Map<String, Any?>): Map<String, String> = values.mapNotNull { (key, value) ->
            val contentId = key.takeIf { it.matches(Regex("ec:[a-z][a-z0-9_]*")) } ?: return@mapNotNull null
            val modelId = value?.toString()?.takeIf { it.matches(Regex("[a-zA-Z0-9_.-]+")) } ?: return@mapNotNull null
            contentId to modelId
        }.toMap()
    }
}

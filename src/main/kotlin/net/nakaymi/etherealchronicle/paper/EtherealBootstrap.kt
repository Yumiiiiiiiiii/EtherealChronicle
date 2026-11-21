package net.nakaymi.etherealchronicle.paper

import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents

/** 同梱データパックをワールドへ展開せずPaperの起動時レジストリへ登録します。 */
class EtherealBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {
        context.lifecycleManager.registerEventHandler(LifecycleEvents.DATAPACK_DISCOVERY) { event ->
            val resource = javaClass.classLoader.getResource(DatapackRegistrar.PATH)
                ?: error("Bundled EtherealChronicle datapack was not found in the plugin jar.")
            event.registrar().discoverPack(resource.toURI(), "provided")
        }
    }
}

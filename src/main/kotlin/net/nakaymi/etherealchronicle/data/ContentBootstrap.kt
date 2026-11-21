package net.nakaymi.etherealchronicle.data

import java.nio.file.Path

data class ContentStartup(val service: ContentService,val usedFallback: Boolean,val problem: String?)
object ContentBootstrap {
    fun initialize(dataFolder: Path,bundled: Map<String,ByteArray>,validate: (ContentSnapshot) -> Unit = {}): ContentStartup {
        val service=ContentService(dataFolder.resolve("content"))
        try {
            service.install(service.load().also(validate))
            return ContentStartup(service,false,null)
        } catch(error: Exception) {
            val digest=DefaultContentInstaller.hash(bundled.toSortedMap().entries.joinToString("\n") { it.key+DefaultContentInstaller.hash(it.value) }.toByteArray())
            val cache=dataFolder.resolve("bundled-cache").resolve(digest)
            DefaultContentInstaller.install(cache,bundled)
            service.install(ContentService(cache.resolve("content")).load().also(validate))
            return ContentStartup(service,true,error.message)
        }
    }
}

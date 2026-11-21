package net.nakaymi.etherealchronicle.data

import java.nio.file.*
import java.security.MessageDigest
import java.util.Properties

/** Non-overwriting installation plus hash-tracked retirement of unchanged shipped defaults. */
object DefaultContentInstaller {
    data class Retirement(val retired: List<String>,val preserved: List<String>)
    fun hash(bytes: ByteArray): String {
        val text=bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").replace("\r\n","\n").trimEnd()+"\n"
        return MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
    private fun resolve(root: Path,name: String): Path {
        val base=root.toAbsolutePath().normalize(); val target=base.resolve(name).normalize()
        require(target.startsWith(base) && target != base) { "配布コンテンツのパスが不正です: $name" }
        return target
    }
    fun manifest(bytes: ByteArray): Map<String,String> {
        val properties=Properties().apply { bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").reader().use(::load) }
        return properties.stringPropertyNames().filter { it != "format" }.associateWith(properties::getProperty)
    }
    private fun installed(root: Path): Map<String,String> {
        val path=root.resolve("content-manifest.properties")
        return if(Files.exists(path)) manifest(Files.readAllBytes(path)) else emptyMap()
    }
    fun retire(root: Path,active: Set<String>,legacy: Map<String,String> = emptyMap()): Retirement {
        val retired=mutableListOf<String>(); val preserved=mutableListOf<String>()
        (legacy+installed(root)).filterKeys { it !in active }.forEach { (name,expected) ->
            val target=resolve(root,name)
            if(!Files.exists(target)) return@forEach
            if(hash(Files.readAllBytes(target)) != expected) { preserved += name; return@forEach }
            val destination=resolve(root,"retired-content/${System.nanoTime()}/$name")
            Files.createDirectories(destination.parent)
            Files.move(target,destination,StandardCopyOption.ATOMIC_MOVE)
            retired += "$name -> $destination"
        }
        return Retirement(retired,preserved)
    }
    fun install(root: Path,bundled: Map<String,ByteArray>): List<String> {
        val changed=mutableListOf<String>()
        bundled.forEach { (name,bytes) ->
            val target=resolve(root,name)
            Files.createDirectories(target.parent)
            try { Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE) }
            catch(_: FileAlreadyExistsException) { if(hash(Files.readAllBytes(target)) != hash(bytes)) changed += name }
        }
        val values=Properties().apply { putAll(installed(root)+bundled.mapValues { hash(it.value) }); setProperty("format","1") }
        Files.createDirectories(root)
        val tmp=root.resolve("content-manifest.properties.tmp")
        Files.newBufferedWriter(tmp).use { values.store(it,"EtherealChronicle installed default file set / normalized SHA-256") }
        Files.move(tmp,root.resolve("content-manifest.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        return changed
    }
}

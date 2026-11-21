package net.nakaymi.etherealchronicle.data

import java.nio.file.*
import java.security.MessageDigest
import java.util.Properties

/** 既存ファイルを初回上書きせず、配布hashで未編集の旧版だけを更新します。 */
object DefaultContentInstaller {
    data class Retirement(val retired: List<String>,val preserved: List<String>)
    data class Installation(val changed: List<String>,val updated: List<String>)
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
    /** 不足ファイルのみ同梱bytesから初回配置し、既存編集を上書きしません。 */
    fun install(root: Path,bundled: Map<String,ByteArray>): List<String> {
        return installTracked(root,bundled).changed
    }
    /** 直前配布版のhashと一致するファイルだけを安全な同梱更新対象にします。 */
    /** 前回同梱hashに一致する旧版だけ更新し、編集済みファイルは保持します。 */
    fun installTracked(root: Path,bundled: Map<String,ByteArray>,legacyDefaults: Map<String,String> = emptyMap()): Installation {
        val changed=mutableListOf<String>(); val updated=mutableListOf<String>()
        val previous=legacyDefaults+installed(root)
        bundled.forEach { (name,bytes) ->
            val target=resolve(root,name)
            Files.createDirectories(target.parent)
            if(!Files.exists(target)) Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE)
            else {
                val current=Files.readAllBytes(target); val currentHash=hash(current); val expected=previous[name]; val newHash=hash(bytes)
                if(currentHash != newHash && expected != null && currentHash == expected) {
                    // 更新前の旧ファイルを履歴へ保管してから、新しい同梱版を配置します。
                    val history=resolve(root,"content-history/${System.nanoTime()}/$name")
                    Files.createDirectories(history.parent); Files.write(history,current,StandardOpenOption.CREATE_NEW)
                    val tmp=target.resolveSibling(target.fileName.toString()+".update.tmp")
                    Files.write(tmp,bytes,StandardOpenOption.CREATE,StandardOpenOption.TRUNCATE_EXISTING)
                    Files.move(tmp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
                    updated += name
                } else if(currentHash != newHash) changed += name
            }
        }
        val values=Properties().apply { putAll(installed(root)+bundled.mapValues { hash(it.value) }); setProperty("format","1") }
        Files.createDirectories(root)
        val tmp=root.resolve("content-manifest.properties.tmp")
        Files.newBufferedWriter(tmp).use { values.store(it,"EtherealChronicle installed default file set / normalized SHA-256") }
        Files.move(tmp,root.resolve("content-manifest.properties"),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)
        return Installation(changed,updated)
    }
}

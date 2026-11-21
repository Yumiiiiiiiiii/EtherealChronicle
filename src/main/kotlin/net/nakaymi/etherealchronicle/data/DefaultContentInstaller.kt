package net.nakaymi.etherealchronicle.data

import java.nio.file.*

/** Installs new defaults only; server edits always win, including across version upgrades. */
object DefaultContentInstaller {
    fun install(root: Path, bundled: Map<String,ByteArray>): List<String> {
        val base = root.toAbsolutePath().normalize()
        val changed = mutableListOf<String>()
        bundled.forEach { (name,bytes) ->
            val target = base.resolve(name).normalize()
            require(target.startsWith(base)) { "配布コンテンツのパスが不正です。" }
            Files.createDirectories(target.parent)
            try { Files.write(target,bytes,StandardOpenOption.CREATE_NEW,StandardOpenOption.WRITE) }
            catch(_: FileAlreadyExistsException) { if(!Files.readAllBytes(target).contentEquals(bytes)) changed += name }
        }
        return changed
    }
}


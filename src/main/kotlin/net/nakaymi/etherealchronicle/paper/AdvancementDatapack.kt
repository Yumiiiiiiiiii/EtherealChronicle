package net.nakaymi.etherealchronicle.paper

import net.nakaymi.etherealchronicle.core.TitleDefinition
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/** 主ワールドのdatapacks内に、所有マーカー付きのプラグイン進捗packだけを書き込みます。 */
internal object AdvancementDatapack {
    const val ROOT_ID="ec:chronicle/root"
    private const val PACK_FOLDER="etherealchronicle_advancements"
    private const val OWNER_MARKER=".etherealchronicle-owned"
    private const val FILE_MANIFEST=".etherealchronicle-files"
    private const val ADVANCEMENT_DIR="data/ec/advancement/chronicle"

    data class Installation(val directory:Path,val packName:String,val changed:Boolean)

    fun install(worldFolder:Path,titles:Collection<TitleDefinition>):Installation {
        // Paper 26のWorld.worldFolderはdimensionごとの場所です。
        // datapackはlevel.datと同じlevel rootから読むため、その親フォルダーを求めます。
        val absolute=worldFolder.toAbsolutePath().normalize()
        val levelRoot=generateSequence(absolute) { it.parent }
            .firstOrNull { Files.isRegularFile(it.resolve("level.dat")) } ?: absolute
        val datapacks=levelRoot.resolve("datapacks")
        Files.createDirectories(datapacks)
        val directory=ownedDirectory(datapacks)
        Files.createDirectories(directory)
        val marker=directory.resolve(OWNER_MARKER)
        if(!Files.exists(marker)) atomicWrite(marker,"EtherealChronicle generated advancement datapack\n")

        val files=linkedMapOf<String,String>()
        files["pack.mcmeta"]="""{"pack":{"description":"EtherealChronicle: title advancements","min_format":[121,0],"max_format":[121,0]}}"""
        files["$ADVANCEMENT_DIR/root.json"]=rootJson()
        titles.filter { it.advancement != null }.sortedBy { it.id }.forEach { title ->
            val advancement=title.advancement!!
            val path="$ADVANCEMENT_DIR/${advancementPath(title.id)}.json"
            files[path]=titleJson(advancement.icon,advancement.title,advancement.description,advancement.frame,
                advancement.parent?.let(::parentKey) ?: ROOT_ID)
        }

        val oldFiles=directory.resolve(FILE_MANIFEST).takeIf(Files::exists)?.let { Files.readAllLines(it).toSet() }.orEmpty()
        var changed=false
        (oldFiles-files.keys).forEach { relative ->
            val target=directory.resolve(relative).normalize()
            if(target.startsWith(directory) && Files.deleteIfExists(target)) changed=true
        }
        files.forEach { (relative,content) ->
            val target=directory.resolve(relative)
            if(!Files.exists(target) || Files.readString(target) != content) { atomicWrite(target,content); changed=true }
        }
        val manifest=files.keys.sorted().joinToString("\n",postfix="\n")
        val manifestPath=directory.resolve(FILE_MANIFEST)
        if(!Files.exists(manifestPath) || Files.readString(manifestPath) != manifest) { atomicWrite(manifestPath,manifest); changed=true }
        return Installation(directory,"file/${directory.fileName}",changed)
    }

    fun advancementId(titleId:String):String="ec:chronicle/${advancementPath(titleId)}"

    private fun ownedDirectory(datapacks:Path):Path {
        var candidate=datapacks.resolve(PACK_FOLDER)
        var suffix=1
        while(Files.exists(candidate) && !Files.exists(candidate.resolve(OWNER_MARKER))) {
            candidate=datapacks.resolve("$PACK_FOLDER-${suffix++}")
        }
        return candidate
    }

    private fun advancementPath(titleId:String):String = titleId.substringAfter(':')
    private fun parentKey(parent:String):String = if(parent.startsWith("ec:title_")) advancementId(parent) else parent

    private fun rootJson():String = """{"display":{"icon":{"id":"minecraft:book"},"title":"EtherealChronicle","description":"冒険の記録","background":"minecraft:textures/gui/advancements/backgrounds/adventure.png","frame":"task","show_toast":false,"announce_to_chat":false,"hidden":false},"criteria":{"root":{"trigger":"minecraft:impossible"}}}"""

    private fun titleJson(icon:String,displayTitle:String,description:String,frame:String,parent:String):String =
        """{"parent":"${escape(parent)}","display":{"icon":{"id":"minecraft:${icon.lowercase()}"},"title":"${escape(displayTitle)}","description":"${escape(description)}","frame":"${frame.lowercase()}","show_toast":true,"announce_to_chat":false,"hidden":false},"criteria":{"profile_condition_met":{"trigger":"minecraft:impossible"}}}"""

    private fun escape(value:String):String = buildString {
        value.forEach { ch -> when(ch) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if(ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
        } }
    }

    private fun atomicWrite(path:Path,value:String) {
        Files.createDirectories(path.parent)
        val temporary=path.resolveSibling("${path.fileName}.tmp")
        Files.writeString(temporary,value)
        try { Files.move(temporary,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING) }
        catch(_:AtomicMoveNotSupportedException) { Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING) }
    }
}

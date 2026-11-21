package net.nakaymi.etherealchronicle.paper

import io.papermc.paper.dialog.Dialog
import io.papermc.paper.registry.data.dialog.ActionButton
import io.papermc.paper.registry.data.dialog.DialogBase
import io.papermc.paper.registry.data.dialog.action.DialogAction
import io.papermc.paper.registry.data.dialog.body.DialogBody
import io.papermc.paper.registry.data.dialog.type.DialogType
import net.nakaymi.etherealchronicle.core.ClassDefinition
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.event.ClickCallback
import org.bukkit.Material
import org.bukkit.entity.Player
import xyz.xenondevs.invui.gui.Gui
import xyz.xenondevs.invui.item.Item
import xyz.xenondevs.invui.item.ItemBuilder
import xyz.xenondevs.invui.window.Window
import java.time.Duration

/** 初回職業一覧をInvUIで表示し、確定操作だけPaper Dialogで受け付けます。 */
class ClassSelectionUi(
    private val theme: UiThemeProvider,
    private val title: (Player) -> Component,
    private val confirmationTitle: (Player, ClassDefinition) -> Component,
    private val confirmationText: (Player, ClassDefinition) -> Component,
    private val confirmLabel: (Player) -> Component,
    private val cancelLabel: (Player) -> Component,
    private val onSelected: (Player, String) -> Unit,
    private val onCancel: (Player) -> Unit,
    private val onClose: (Player) -> Unit,
) {
    fun open(player: Player, classes: List<ClassDefinition>) {
        val available = classes.take(9)
        val builder = Gui.builder().setStructure("a b c d e f g h i")
        available.forEachIndexed { index, definition ->
            val letter = ('a'.code + index).toChar()
            val material = definition.icon?.let { runCatching { Material.valueOf(it) }.getOrNull() }
                ?: fallback(definition.resource.name)
            val itemStack = theme.icon(player, material, definition.itemModel)
            itemStack.editMeta { it.displayName(Component.text(definition.name)) }
            val item = Item.builder().setItemProvider(ItemBuilder(itemStack)).addClickHandler { click ->
                confirm(player, definition)
            }.build()
            builder.addIngredient(letter, item)
        }
        val window = Window.builder()
            .setViewer(player)
            .setTitle(theme.title(player, title(player)))
            .setUpperGui(builder.build())
            .build()
        window.addCloseHandler { onClose(player) }
        window.open()
    }

    private fun confirm(player: Player, definition: ClassDefinition) {
        val options = ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build()
        val yes = ActionButton.builder(confirmLabel(player)).width(200)
            .action(DialogAction.customClick({ _, audience ->
                (audience as? Player)?.let { onSelected(it, definition.id) }
            }, options)).build()
        val no = ActionButton.builder(cancelLabel(player)).width(200)
            .action(DialogAction.customClick({ _, audience ->
                (audience as? Player)?.let(onCancel)
            }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(2)).build())).build()
        val dialog = Dialog.create { entry ->
            entry.empty().base(DialogBase.builder(confirmationTitle(player, definition))
                .body(listOf(DialogBody.plainMessage(confirmationText(player, definition))))
                .canCloseWithEscape(true)
                .build())
                .type(DialogType.confirmation(yes, no))
        }
        player.showDialog(dialog)
    }

    private fun fallback(resource: String): Material = when (resource) {
        "RAGE" -> Material.IRON_SWORD
        "FOCUS" -> Material.BOW
        else -> Material.BLAZE_ROD
    }
}

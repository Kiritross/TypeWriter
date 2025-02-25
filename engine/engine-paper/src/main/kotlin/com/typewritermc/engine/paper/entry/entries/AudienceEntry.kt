package com.typewritermc.engine.paper.entry.entries

import com.typewritermc.core.entries.Ref
import com.typewritermc.core.entries.priority
import com.typewritermc.core.extension.annotations.Help
import com.typewritermc.core.extension.annotations.Tags
import com.typewritermc.engine.paper.entry.AudienceManager
import com.typewritermc.engine.paper.entry.ManifestEntry
import com.typewritermc.engine.paper.plugin
import lirand.api.extensions.events.unregister
import lirand.api.extensions.server.server
import org.bukkit.entity.Player
import org.bukkit.event.Listener
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.java.KoinJavaComponent.get
import java.util.*
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.reflect.KClass


/**
 * When an `AudienceEntry` is marked with `@ChildOnly`, it will only be used as a child of an `AudienceFilterEntry`.
 * If the entry is at the root of the manifest, it will not be instantiated.
 */
@Target(AnnotationTarget.CLASS)
annotation class ChildOnly

@Tags("audience")
interface AudienceEntry : ManifestEntry {
    fun display(): AudienceDisplay
}


@Tags("audience_filter")
interface AudienceFilterEntry : AudienceEntry {
    val children: List<Ref<out AudienceEntry>>
    override fun display(): AudienceFilter
}

interface Invertible {
    @Help("The audience will be the players that do not match the criteria.")
    val inverted: Boolean
}

interface TickableDisplay {
    fun tick()
}

enum class AudienceDisplayState(val displayName: String, val color: String) {
    // When the player is in the audience
    IN_AUDIENCE("In Audience", "green"),

    // When the player is not passing the audience filter
    BLOCKED("Blocked", "red"),

    // When all the parents block the player
    NOT_CONSIDERED("Not Considered", "gray"),
    ;
}

abstract class AudienceDisplay : Listener {
    var isActive = false
        private set
    private val playerIds: ConcurrentSkipListSet<UUID> = ConcurrentSkipListSet()
    open val players: List<Player> get() = server.onlinePlayers.filter { it.uniqueId in playerIds }

    open fun displayState(player: Player): AudienceDisplayState {
        if (player.uniqueId in playerIds) return AudienceDisplayState.IN_AUDIENCE
        return AudienceDisplayState.NOT_CONSIDERED
    }

    open fun initialize() {
        if (isActive) return
        isActive = true
        server.pluginManager.registerEvents(this, plugin)
    }

    open fun dispose() {
        if (!isActive) return
        isActive = false
        players.forEach { removePlayer(it) }
        this.unregister()
    }

    fun addPlayer(player: Player) {
        if (playerIds.isEmpty()) initialize()
        if (!playerIds.add(player.uniqueId)) return
        onPlayerAdd(player)
    }

    fun removePlayer(player: Player) {
        if (!playerIds.remove(player.uniqueId)) return
        onPlayerRemove(player)
        if (playerIds.isEmpty()) dispose()
    }

    abstract fun onPlayerAdd(player: Player)
    abstract fun onPlayerRemove(player: Player)

    open operator fun contains(player: Player): Boolean = player.uniqueId in playerIds
    open operator fun contains(uuid: UUID): Boolean = uuid in playerIds
}

class PassThroughDisplay : AudienceDisplay() {
    override fun onPlayerAdd(player: Player) {}
    override fun onPlayerRemove(player: Player) {}
}


abstract class AudienceFilter(
    private val ref: Ref<out AudienceFilterEntry>
) : AudienceDisplay() {
    private val inverted = (ref.get() as? Invertible)?.inverted ?: false
    private val filteredPlayers: ConcurrentSkipListSet<UUID> = ConcurrentSkipListSet()
    override val players: List<Player> get() = server.onlinePlayers.filter { it.uniqueId in filteredPlayers }

    protected val consideredPlayers: List<Player> get() = super.players

    override fun displayState(player: Player): AudienceDisplayState {
        if (player in this) return AudienceDisplayState.IN_AUDIENCE
        if (canConsider(player)) return AudienceDisplayState.BLOCKED
        return AudienceDisplayState.NOT_CONSIDERED
    }

    abstract fun filter(player: Player): Boolean

    fun Player.updateFilter(isFiltered: Boolean) {
        val allow = !inverted == isFiltered && canConsider(this)
        if (allow) {
            if (filteredPlayers.add(uniqueId)) {
                onPlayerFilterAdded(this)
                get<AudienceManager>(AudienceManager::class.java).addPlayerToChildren(this, ref)
            }
        } else {
            if (filteredPlayers.remove(uniqueId)) {
                onPlayerFilterRemoved(this)
                get<AudienceManager>(AudienceManager::class.java).removePlayerFromChildren(this, ref)
            }
        }
    }

    fun Player.refresh() = updateFilter(filter(this))

    override fun onPlayerAdd(player: Player) {
        player.refresh()
    }

    override fun onPlayerRemove(player: Player) {
        player.updateFilter(false)
    }

    open fun onPlayerFilterAdded(player: Player) {
    }

    open fun onPlayerFilterRemoved(player: Player) {
    }

    fun canConsider(player: Player): Boolean = super.contains(player)
    fun canConsider(uuid: UUID): Boolean = super.contains(uuid)

    override fun contains(player: Player): Boolean = contains(player.uniqueId)
    override fun contains(uuid: UUID): Boolean = uuid in filteredPlayers
}

class PassThroughFilter(
    ref: Ref<out AudienceFilterEntry>
) : AudienceFilter(ref) {
    override fun filter(player: Player): Boolean = true
}

/**
 * Filters and displays at most one display entry type to the player.
 * This is useful for displaying a single sidebar, or a tab list header/footer.
 */
abstract class SingleFilter<E : AudienceFilterEntry, D : PlayerSingleDisplay<E>>(
    internal val ref: Ref<E>,
    private val createDisplay: (Player) -> D
) : AudienceFilter(ref), TickableDisplay {
    // Map needs to be shared between all instances of the display
    protected abstract val displays: MutableMap<UUID, D>

    override fun filter(player: Player): Boolean = displays[player.uniqueId]?.ref == ref
    override fun tick() {
        displays.values.forEach { it.tick() }
    }

    override fun onPlayerAdd(player: Player) {
        displays.computeIfAbsent(player.uniqueId)
        {
            createDisplay(player)
                .also { it.initialize() }
        }
            .onAddedBy(ref)
        super.onPlayerAdd(player)
    }

    override fun onPlayerRemove(player: Player) {
        super.onPlayerRemove(player)
        displays.computeIfPresent(player.uniqueId) { _, display ->
            if (display.onRemovedBy(ref)) {
                display.dispose()
                null
            } else {
                display
            }
        }
    }
}

abstract class PlayerSingleDisplay<E : AudienceFilterEntry>(
    protected val player: Player,
    private val displayKClass: KClass<out SingleFilter<E, *>>,
    private var current: Ref<E>,
) : KoinComponent {
    private val audienceManager: AudienceManager by inject()
    val ref: Ref<E> get() = current

    /**
     * Called when the player is added to a display for the first time
     */
    open fun initialize() {
        setup()
    }

    /**
     * Called everytime the player is added to a display.
     * Either after [initialize] or when the display changed for the player
     */
    open fun setup() {
        val filter = audienceManager[ref] as? AudienceFilter? ?: return
        with(filter) {
            player.updateFilter(true)
        }
    }

    /**
     * Called every tick
     */
    open fun tick() {}

    /**
     * Called when the player is removed from a display
     */
    open fun tearDown() {
        val filter = audienceManager[ref] as? AudienceFilter? ?: return
        with(filter) {
            player.updateFilter(false)
        }
    }

    /**
     * Called when the player is no longer in any display
     */
    open fun dispose() {
        tearDown()
    }

    fun onAddedBy(ref: Ref<E>): Boolean {
        if (current.priority > ref.priority) return false
        if (ref == current) return false
        tearDown()
        current = ref
        setup()
        return true
    }

    /**
     * @return true if the display should be removed
     */
    fun onRemovedBy(ref: Ref<E>): Boolean {
        if (current != ref) return false
        val new = audienceManager.findDisplays(displayKClass)
            .filter { it.canConsider(player) }
            .maxByOrNull { it.ref.priority }
        if (new == null) {
            return true
        }
        tearDown()
        current = new.ref
        setup()
        return false
    }
}

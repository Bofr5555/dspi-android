package se.bofr.dspi.app

import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavHostController

/** The few back-stack facts [EntryNavigator] needs; [NavHostController] in the app, a list in tests. */
internal interface EntryBackStack {
    /** Id of the entry on top of the stack, or null when it is empty. */
    val currentId: String?

    /** Whether there is a screen under the current one to return to. */
    val canPop: Boolean

    fun pop()

    fun navigate(route: String)
}

/**
 * The in-app back and navigate actions handed to the screen of one back-stack entry ([ownId]).
 *
 * They act only while that entry is the current one. Navigation Compose keeps the outgoing
 * screen on screen, and tappable, through its exit transition; without this guard a second
 * tap on a "‹ Back" button (or ChannelScreen's own return-to-home racing a tap) popped again.
 * Popping the start destination empties the NavHost: a blank page under the app bar that no
 * in-app control can leave (system back is unaffected, it never pops the last entry). The
 * same double tap on a row that opens a page pushed that page twice, so back seemed to do
 * nothing. Back also never pops the start destination itself, as system back.
 */
internal class EntryNavigator(private val stack: EntryBackStack, private val ownId: String) {
    private val isCurrent get() = stack.currentId == ownId

    fun back() {
        if (isCurrent && stack.canPop) stack.pop()
    }

    fun go(route: String) {
        if (isCurrent) stack.navigate(route)
    }
}

internal class NavControllerBackStack(private val nav: NavHostController) : EntryBackStack {
    override val currentId: String? get() = nav.currentBackStackEntry?.id
    override val canPop: Boolean get() = nav.previousBackStackEntry != null

    override fun pop() {
        nav.popBackStack()
    }

    override fun navigate(route: String) = nav.navigate(route)
}

internal fun NavHostController.navigatorFor(entry: NavBackStackEntry) = EntryNavigator(NavControllerBackStack(this), entry.id)

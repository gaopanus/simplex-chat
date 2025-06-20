package chat.simplex.common.platform

// Placeholder NavController interface
interface NavController {
    fun navigateUp()
    fun navigate(route: String)
    // Add other common navigation actions if needed by multiple views
}

// A dummy NavController if a real one isn't easily injectable/available
// or for use in previews.
class DummyNavController(private val onUp: (() -> Unit)? = null, private val onNavigate: ((String) -> Unit)? = null) : NavController {
    override fun navigateUp() {
        onUp?.invoke()
        println("DummyNavController: navigateUp called")
    }

    override fun navigate(route: String) {
        onNavigate?.invoke(route)
        println("DummyNavController: navigate called with route: $route")
    }
}

package app.folio.desktop

import java.lang.reflect.Proxy
import javax.swing.JComponent
import javax.swing.SwingUtilities

/**
 * Trackpad pinch on macOS. The JDK reports it through `com.apple.eawt.event`, which isn't an
 * exported package, so it is reached by reflection (the app is started with --add-exports for
 * it). Where that isn't available (another OS, a locked-down runtime) pinch simply does nothing;
 * Cmd + scroll and Cmd +/−/0 still zoom.
 */
object Trackpad {
    /** Called on the UI thread with each pinch step: > 0 spreading (zoom in), < 0 pinching. */
    @Volatile var onMagnify: ((Float) -> Unit)? = null

    fun install(component: JComponent): Boolean = try {
        val listenerType = Class.forName("com.apple.eawt.event.MagnificationListener")
        val base = Class.forName("com.apple.eawt.event.GestureListener")
        val listener = Proxy.newProxyInstance(listenerType.classLoader, arrayOf(listenerType)) { _, method, args ->
            if (method.name == "magnify" && args != null && args.isNotEmpty()) {
                val amount = (args[0].javaClass.getMethod("getMagnification").invoke(args[0]) as Double).toFloat()
                SwingUtilities.invokeLater { onMagnify?.invoke(amount) }
            }
            null
        }
        Class.forName("com.apple.eawt.event.GestureUtilities")
            .getMethod("addGestureListenerTo", JComponent::class.java, base).invoke(null, component, listener)
        true
    } catch (e: Throwable) {
        false
    }
}

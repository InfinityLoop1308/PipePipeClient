package org.schabi.newpipe.plugin

import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/**
 * ObjectInputStream that can also resolve classes defined by loaded plugins. Use it for
 * every stream that may contain extractor objects.
 */
class PluginObjectInputStream(input: InputStream) : ObjectInputStream(input) {
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        return try {
            super.resolveClass(desc)
        } catch (e: ClassNotFoundException) {
            PluginManager.findPluginClass(desc.name) ?: throw e
        }
    }
}

package org.schabi.newpipe.settings

import java.io.IOException
import java.io.InputStream
import java.io.ObjectInputStream
import java.io.ObjectStreamClass

/**
 * An [ObjectInputStream] that only allows preferences-related types (boxed primitives,
 * HashMap, HashSet) to be deserialized, to prevent injections from untrusted backups.
 */
class PreferencesObjectInputStream(stream: InputStream) : ObjectInputStream(stream) {
    @Throws(ClassNotFoundException::class, IOException::class)
    override fun resolveClass(desc: ObjectStreamClass): Class<*> {
        if (desc.name in CLASS_WHITELIST) {
            return super.resolveClass(desc)
        }
        throw ClassNotFoundException("Class not allowed: ${desc.name}")
    }

    companion object {
        private val CLASS_WHITELIST = setOf(
            "java.lang.Boolean",
            "java.lang.Byte",
            "java.lang.Character",
            "java.lang.Short",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Float",
            "java.lang.Double",
            "java.lang.Void",
            "java.lang.Number",
            "java.util.HashMap",
            "java.util.HashSet"
        )
    }
}

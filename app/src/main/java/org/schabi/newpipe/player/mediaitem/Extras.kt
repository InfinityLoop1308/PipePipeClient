package org.schabi.newpipe.player.mediaitem

import java.io.Serializable

/**
 * Immutable, typed, multi-slot container attached to a [PlayerMediaItem].
 *
 * This is the single channel through which player strategies exchange data: a strategy
 * publishes its result under its own [Key] and other strategies read it back, without ever
 * holding a reference to each other. Adding or replacing a value returns a new [Extras]
 * instance, so the owning media item stays immutable.
 *
 * Java callers use [EMPTY], [get], [plus], [minus], [has] and [asMap] directly.
 */
class Extras private constructor(
    private val values: Map<Key<*>, Any?>,
) : Serializable {

    /**
     * A type-safe key identifying one slot in an [Extras] bag.
     *
     * Keys are compared by name, so a key can still be used after serialization or from a
     * different module as long as the same name is used.
     */
    class Key<T>(val name: String) : Serializable {
        override fun equals(other: Any?): Boolean =
            other is Key<*> && name == other.name

        override fun hashCode(): Int = name.hashCode()

        override fun toString(): String = "Extras.Key($name)"
    }

    /**
     * @return the value stored under [key], or `null` if the slot is empty
     */
    @Suppress("UNCHECKED_CAST")
    operator fun <T> get(key: Key<T>): T? = values[key] as T?

    fun has(key: Key<*>): Boolean = values.containsKey(key)

    /**
     * Returns a new [Extras] with [value] stored under [key], replacing any previous value.
     */
    fun <T> plus(key: Key<T>, value: T?): Extras = Extras(values + (key to value))

    /**
     * Returns a new [Extras] with the slot for [key] removed.
     */
    fun <T> minus(key: Key<T>): Extras =
        if (!values.containsKey(key)) this else Extras(values - key)

    fun isEmpty(): Boolean = values.isEmpty()

    fun asMap(): Map<Key<*>, Any?> = values

    override fun equals(other: Any?): Boolean =
        other is Extras && values == other.values

    override fun hashCode(): Int = values.hashCode()

    override fun toString(): String = "Extras" + values.keys

    companion object {
        @JvmField
        val EMPTY = Extras(emptyMap())
    }
}

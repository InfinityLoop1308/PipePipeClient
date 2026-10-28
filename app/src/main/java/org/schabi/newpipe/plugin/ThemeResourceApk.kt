package org.schabi.newpipe.plugin

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

/**
 * Builds a minimal resource-only APK that contains a single resource type holding complex
 * `style` entries. It replaces a general purpose ARSC library because the table needed for plugin
 * themes is tiny and always has the same shape.
 *
 * The output is deterministic, so it can be named by a hash of its content.
 */
internal object ThemeResourceApk {
    const val TYPE_COLOR_ARGB8 = 0x1c

    class Item(val attr: Int, val dataType: Int, val data: Int)

    class Style(val name: String, val items: List<Item>)

    private const val RES_STRING_POOL = 0x0001
    private const val RES_TABLE = 0x0002
    private const val RES_XML = 0x0003
    private const val RES_XML_START_ELEMENT = 0x0102
    private const val RES_XML_END_ELEMENT = 0x0103
    private const val RES_TABLE_PACKAGE = 0x0200
    private const val RES_TABLE_TYPE = 0x0201
    private const val RES_TABLE_TYPE_SPEC = 0x0202

    private const val PACKAGE_HEADER_SIZE = 288
    private const val CONFIG_SIZE = 28
    private const val TYPE_HEADER_SIZE = 20 + CONFIG_SIZE
    private const val FLAG_COMPLEX = 0x0001
    private const val SPEC_PUBLIC = 0x40000000
    private const val NO_ENTRY = -1

    /**
     * @param packageName name of the package. Must equal the app's package so the table is merged
     * into the app's resources.
     * @param packageId id of the package, must equal the app's package id for the same reason.
     * @param typeId id of a type that the app does not use itself. Entry `i` of [styles] gets the
     * resource id `packageId << 24 | typeId << 16 | i`.
     */
    fun build(packageName: String, packageId: Int, typeId: Int, styles: List<Style>): ByteArray {
        val arsc = table(packageName, packageId, typeId, styles)
        return zip(
            listOf(
                "resources.arsc" to arsc,
                "AndroidManifest.xml" to manifest(packageName)
            )
        )
    }

    private class Out {
        val bytes = ByteArrayOutputStream()
        val size get() = bytes.size()

        fun u8(v: Int) = bytes.write(v)

        fun u16(v: Int) {
            bytes.write(v)
            bytes.write(v ushr 8)
        }

        fun u32(v: Int) {
            u16(v)
            u16(v ushr 16)
        }

        fun raw(b: ByteArray) = bytes.write(b)

        fun pad4() {
            while (size % 4 != 0) u8(0)
        }

        fun toByteArray(): ByteArray = bytes.toByteArray()
    }

    private fun stringPool(strings: List<String>): ByteArray {
        val data = Out()
        val offsets = IntArray(strings.size)
        strings.forEachIndexed { i, s ->
            require(s.length < 0x8000) { "String too long" }
            offsets[i] = data.size
            data.u16(s.length)
            s.forEach { data.u16(it.code) }
            data.u16(0)
        }
        data.pad4()
        val stringsStart = 28 + 4 * strings.size
        val out = Out()
        out.u16(RES_STRING_POOL)
        out.u16(28)
        out.u32(stringsStart + data.size)
        out.u32(strings.size)
        out.u32(0) // style count
        out.u32(0) // flags: UTF-16
        out.u32(if (strings.isEmpty()) 0 else stringsStart)
        out.u32(0) // styles start
        offsets.forEach { out.u32(it) }
        out.raw(data.toByteArray())
        return out.toByteArray()
    }

    private fun table(
        packageName: String,
        packageId: Int,
        typeId: Int,
        styles: List<Style>
    ): ByteArray {
        // The type pool is indexed by (type id - 1); only the position of our type matters.
        val typeNames = List(typeId) { if (it == typeId - 1) "style" else "" }
        val typePool = stringPool(typeNames)
        val keyPool = stringPool(styles.map { it.name })

        val entryCount = styles.size
        val entries = Out()
        val offsets = IntArray(entryCount)
        styles.forEachIndexed { index, style ->
            offsets[index] = entries.size
            // Sorted by attribute id, as aapt does. Bag lookup relies on that order.
            val items = style.items.sortedWith { a, b -> Integer.compareUnsigned(a.attr, b.attr) }
            entries.u16(16) // ResTable_map_entry size
            entries.u16(FLAG_COMPLEX)
            entries.u32(index) // key string index
            entries.u32(0) // parent
            entries.u32(items.size)
            items.forEach {
                entries.u32(it.attr)
                entries.u16(8) // Res_value size
                entries.u8(0)
                entries.u8(it.dataType)
                entries.u32(it.data)
            }
        }

        val spec = Out()
        spec.u16(RES_TABLE_TYPE_SPEC)
        spec.u16(16)
        spec.u32(16 + 4 * entryCount)
        spec.u8(typeId)
        spec.u8(0)
        spec.u16(0)
        spec.u32(entryCount)
        repeat(entryCount) { spec.u32(SPEC_PUBLIC) }

        val entriesStart = TYPE_HEADER_SIZE + 4 * entryCount
        val type = Out()
        type.u16(RES_TABLE_TYPE)
        type.u16(TYPE_HEADER_SIZE)
        type.u32(entriesStart + entries.size)
        type.u8(typeId)
        type.u8(0) // flags: dense
        type.u16(0)
        type.u32(entryCount)
        type.u32(entriesStart)
        type.u32(CONFIG_SIZE) // default config: size followed by zeros
        repeat(CONFIG_SIZE - 4) { type.u8(0) }
        offsets.forEach { type.u32(it) }
        type.raw(entries.toByteArray())

        val pkg = Out()
        pkg.u16(RES_TABLE_PACKAGE)
        pkg.u16(PACKAGE_HEADER_SIZE)
        val pkgBodySize = typePool.size + keyPool.size + spec.size + type.size
        pkg.u32(PACKAGE_HEADER_SIZE + pkgBodySize)
        pkg.u32(packageId)
        val name = packageName.take(127)
        repeat(128) { pkg.u16(if (it < name.length) name[it].code else 0) }
        pkg.u32(PACKAGE_HEADER_SIZE) // type strings
        pkg.u32(typeNames.size) // last public type
        pkg.u32(PACKAGE_HEADER_SIZE + typePool.size) // key strings
        pkg.u32(styles.size) // last public key
        pkg.u32(0) // type id offset
        pkg.raw(typePool)
        pkg.raw(keyPool)
        pkg.raw(spec.toByteArray())
        pkg.raw(type.toByteArray())

        val globalPool = stringPool(emptyList())
        val out = Out()
        out.u16(RES_TABLE)
        out.u16(12)
        out.u32(12 + globalPool.size + pkg.size)
        out.u32(1) // package count
        out.raw(globalPool)
        out.raw(pkg.toByteArray())
        return out.toByteArray()
    }

    /** Binary XML for `<manifest package="..."/>`. */
    private fun manifest(packageName: String): ByteArray {
        val pool = stringPool(listOf("package", "manifest", packageName))
        val start = Out()
        start.u16(RES_XML_START_ELEMENT)
        start.u16(16)
        start.u32(16 + 20 + 20)
        start.u32(1) // line number
        start.u32(NO_ENTRY) // comment
        start.u32(NO_ENTRY) // namespace
        start.u32(1) // name: manifest
        start.u16(20) // attribute start
        start.u16(20) // attribute size
        start.u16(1) // attribute count
        start.u16(0)
        start.u16(0)
        start.u16(0)
        start.u32(NO_ENTRY) // attribute namespace
        start.u32(0) // attribute name: package
        start.u32(2) // raw value
        start.u16(8)
        start.u8(0)
        start.u8(0x03) // string
        start.u32(2)

        val end = Out()
        end.u16(RES_XML_END_ELEMENT)
        end.u16(16)
        end.u32(24)
        end.u32(1)
        end.u32(NO_ENTRY)
        end.u32(NO_ENTRY)
        end.u32(1)

        val out = Out()
        out.u16(RES_XML)
        out.u16(8)
        out.u32(8 + pool.size + start.size + end.size)
        out.raw(pool)
        out.raw(start.toByteArray())
        out.raw(end.toByteArray())
        return out.toByteArray()
    }

    /**
     * Stored (uncompressed) zip. Data of every entry starts at a 4 byte boundary, which Android
     * expects for resources.arsc.
     */
    private fun zip(files: List<Pair<String, ByteArray>>): ByteArray {
        val out = Out()
        val central = Out()
        for ((name, data) in files) {
            val nameBytes = name.toByteArray(Charsets.UTF_8)
            val offset = out.size
            val pad = (4 - (offset + 30 + nameBytes.size) % 4) % 4
            val crc = CRC32().apply { update(data) }.value.toInt()

            out.u32(0x04034b50)
            out.u16(10) // version needed
            out.u16(0) // flags
            out.u16(0) // stored
            out.u16(0) // time
            out.u16(0x21) // date: 1980-01-01
            out.u32(crc)
            out.u32(data.size)
            out.u32(data.size)
            out.u16(nameBytes.size)
            out.u16(pad)
            out.raw(nameBytes)
            repeat(pad) { out.u8(0) }
            out.raw(data)

            central.u32(0x02014b50)
            central.u16(20) // version made by
            central.u16(10)
            central.u16(0)
            central.u16(0)
            central.u16(0)
            central.u16(0x21)
            central.u32(crc)
            central.u32(data.size)
            central.u32(data.size)
            central.u16(nameBytes.size)
            central.u16(0) // extra
            central.u16(0) // comment
            central.u16(0) // disk
            central.u16(0) // internal attributes
            central.u32(0) // external attributes
            central.u32(offset)
            central.raw(nameBytes)
        }
        val centralOffset = out.size
        out.raw(central.toByteArray())
        out.u32(0x06054b50)
        out.u16(0)
        out.u16(0)
        out.u16(files.size)
        out.u16(files.size)
        out.u32(central.size)
        out.u32(centralOffset)
        out.u16(0)
        return out.toByteArray()
    }
}

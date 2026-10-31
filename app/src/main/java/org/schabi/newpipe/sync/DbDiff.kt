package org.schabi.newpipe.sync

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import android.util.JsonWriter
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.sqlite.db.SupportSQLiteStatement

/**
 * Row level diff between two SQLite files with the same schema, keyed by primary key.
 * Values are Long, Double, String, [Blob] or null so rows compare with plain equals().
 */
object DbDiff {
    data class Blob(val b64: String)

    class Table(val name: String, val columns: List<String>, val pk: List<String>)

    /** A connection to read from: a plain [SQLiteDatabase] or Room's, so reads can join its transaction. */
    fun interface Reader {
        fun query(sql: String): Cursor
    }

    fun SQLiteDatabase.reader() = Reader { rawQuery(it, null) }
    fun SupportSQLiteDatabase.reader() = Reader { query(it) }

    class Ops(val table: Table) {
        val upserts = ArrayList<List<Any?>>()
        val deletes = ArrayList<List<Any?>>()
        val isEmpty get() = upserts.isEmpty() && deletes.isEmpty()
    }

    /** A column that exists on one side only; never equal to anything, so the row is re-sent. */
    private object Missing

    private val SKIPPED = setOf("android_metadata", "room_master_table", "sqlite_sequence")

    fun q(id: String) = "\"" + id.replace("\"", "\"\"") + "\""

    /** User tables with a declared primary key. Room's bookkeeping tables are skipped. */
    fun tables(db: Reader): List<Table> {
        val names = ArrayList<String>()
        db.query("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name")
            .use { while (it.moveToNext()) names += it.getString(0) }
        return names.filter { it !in SKIPPED && !it.startsWith("room_") }.mapNotNull { tableInfo(db, it) }
    }

    fun tableInfo(db: Reader, name: String): Table? {
        val cols = ArrayList<String>()
        val pk = ArrayList<Pair<Int, String>>()
        db.query("PRAGMA table_info(${q(name)})").use {
            while (it.moveToNext()) {
                val col = it.getString(1)
                cols += col
                val pos = it.getInt(5)
                if (pos > 0) pk += pos to col
            }
        }
        if (cols.isEmpty() || pk.isEmpty()) return null
        return Table(name, cols, pk.sortedBy { it.first }.map { it.second })
    }

    /** CREATE statements of the schema, so a fresh server can build its database. */
    fun schema(db: Reader): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        db.query(
            "SELECT type, sql FROM sqlite_master WHERE sql IS NOT NULL AND name NOT LIKE 'sqlite_%' " +
                "AND tbl_name NOT IN ('android_metadata','room_master_table') ORDER BY type='table' DESC, name"
        ).use { while (it.moveToNext()) out += it.getString(0) to it.getString(1) }
        return out
    }

    fun userVersion(db: Reader): Int =
        db.query("PRAGMA user_version").use { it.moveToFirst(); it.getInt(0) }

    private fun value(c: Cursor, i: Int): Any? = when (c.getType(i)) {
        Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
        Cursor.FIELD_TYPE_STRING -> c.getString(i)
        Cursor.FIELD_TYPE_BLOB -> Blob(Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP))
        else -> null
    }

    /** Streams rows of [table] from [db]; columns absent from [db] read as [Missing]. */
    private fun forEachRow(db: Reader?, table: Table, block: (List<Any?>) -> Unit) {
        if (db == null) return
        val present = tableInfo(db, table.name)?.columns?.toSet() ?: return
        val select = table.columns.joinToString(",") { if (it in present) q(it) else "NULL" }
        val missing = table.columns.map { it !in present }
        db.query("SELECT $select FROM ${q(table.name)}").use { c ->
            while (c.moveToNext()) {
                block(table.columns.indices.map { if (missing[it]) Missing else value(c, it) })
            }
        }
    }

    /**
     * Emits what turns [from] into [to] for one table: rows of [to] that are new or changed, and
     * primary keys that are only in [from]. [from] may be null (everything in [to] is new).
     */
    fun diff(
        from: Reader?,
        to: Reader,
        table: Table,
        onUpsert: (List<Any?>) -> Unit,
        onDelete: (List<Any?>) -> Unit
    ) {
        val pkIdx = table.pk.map { table.columns.indexOf(it) }
        val old = HashMap<List<Any?>, List<Any?>>()
        forEachRow(from, table) { row -> old[pkIdx.map { row[it] }] = row }
        forEachRow(to, table) { row ->
            val key = pkIdx.map { row[it] }
            val prev = old.remove(key)
            if (prev == null || prev != row || row.any { it === Missing }) onUpsert(row)
        }
        old.keys.forEach(onDelete)
    }

    fun collect(from: Reader?, to: Reader, table: Table): Ops =
        Ops(table).also { ops -> diff(from, to, table, { ops.upserts += it }, { ops.deletes += it }) }

    /**
     * Emits each changed row of [table] as (row in [from], row in [to]); null on the side where the
     * row is absent. With [listColumn], a list (rows sharing that column's value) with any change
     * is emitted whole, unchanged rows included, so the server sees the entire old and new list.
     */
    fun changes(
        from: Reader?,
        to: Reader,
        table: Table,
        listColumn: String?,
        onChange: (List<Any?>?, List<Any?>?) -> Unit
    ) {
        val pkIdx = table.pk.map { table.columns.indexOf(it) }
        val g = listColumn?.let { table.columns.indexOf(it) }?.takeIf { it >= 0 }
        val old = HashMap<List<Any?>, List<Any?>>()
        forEachRow(from, table) { row -> old[pkIdx.map { row[it] }] = row }
        val unchanged = ArrayList<List<Any?>>()
        val touched = HashSet<Any?>()
        forEachRow(to, table) { row ->
            val prev = old.remove(pkIdx.map { row[it] })
            if (prev == null || prev != row || prev.any { it === Missing }) {
                onChange(prev, row)
                if (g != null) touched += row[g]
            } else if (g != null) {
                unchanged += row
            }
        }
        old.values.forEach { prev ->
            onChange(prev, null)
            if (g != null) touched += prev[g]
        }
        if (g != null) unchanged.filter { it[g] in touched }.forEach { onChange(it, it) }
    }

    fun writeValue(w: JsonWriter, v: Any?) {
        when (v) {
            null -> w.nullValue()
            Missing -> w.beginObject().name("missing").value(true).endObject()
            is Long -> w.value(v)
            is Double -> w.value(v)
            is String -> w.value(v)
            is Blob -> w.beginObject().name("b64").value(v.b64).endObject()
            else -> throw IllegalArgumentException("Unexpected value $v")
        }
    }

    private fun bind(st: SupportSQLiteStatement, values: List<Any?>) {
        st.clearBindings()
        values.forEachIndexed { i, v ->
            when (v) {
                null -> st.bindNull(i + 1)
                is Long -> st.bindLong(i + 1, v)
                is Double -> st.bindDouble(i + 1, v)
                is String -> st.bindString(i + 1, v)
                is Blob -> st.bindBlob(i + 1, Base64.decode(v.b64, Base64.NO_WRAP))
                else -> throw IllegalArgumentException("Unexpected value $v")
            }
        }
    }

    /**
     * Applies [ops] inside the caller's transaction: deletes first, then update-or-insert by
     * primary key. INSERT OR REPLACE is avoided on purpose because its implicit delete fires
     * ON DELETE CASCADE and would wipe child rows. Foreign keys are checked before commit.
     */
    fun apply(db: SupportSQLiteDatabase, ops: List<Ops>) {
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        for (o in ops) {
            if (o.deletes.isEmpty()) continue
            val where = o.table.pk.joinToString(" AND ") { "${q(it)} IS ?" }
            val st = db.compileStatement("DELETE FROM ${q(o.table.name)} WHERE $where")
            o.deletes.forEach { bind(st, it); st.executeUpdateDelete() }
            st.close()
        }
        for (o in ops) {
            if (o.upserts.isEmpty()) continue
            val t = o.table
            val pkIdx = t.pk.map { t.columns.indexOf(it) }
            val nonPk = t.columns.indices.filter { it !in pkIdx }
            val update = if (nonPk.isEmpty()) null else db.compileStatement(
                "UPDATE ${q(t.name)} SET " + nonPk.joinToString(",") { "${q(t.columns[it])}=?" } +
                    " WHERE " + t.pk.joinToString(" AND ") { "${q(it)} IS ?" }
            )
            val insert = db.compileStatement(
                "INSERT${if (nonPk.isEmpty()) " OR IGNORE" else ""} INTO ${q(t.name)} (" +
                    t.columns.joinToString(",") { q(it) } + ") VALUES (" +
                    t.columns.joinToString(",") { "?" } + ")"
            )
            for (row in o.upserts) {
                var changed = 0
                if (update != null) {
                    bind(update, nonPk.map { row[it] } + pkIdx.map { row[it] })
                    changed = update.executeUpdateDelete()
                }
                if (changed == 0) {
                    bind(insert, row)
                    insert.executeInsert()
                }
            }
            update?.close()
            insert.close()
        }
        checkForeignKeys(db)
    }

    /** Fallback when row order trips a unique index: rewrite every table from [src]. */
    fun replaceAll(db: SupportSQLiteDatabase, src: Reader, tables: List<Table>) {
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        tables.forEach { db.execSQL("DELETE FROM ${q(it.name)}") }
        for (t in tables) {
            val insert = db.compileStatement(
                "INSERT INTO ${q(t.name)} (" + t.columns.joinToString(",") { q(it) } +
                    ") VALUES (" + t.columns.joinToString(",") { "?" } + ")"
            )
            forEachRow(src, t) { bind(insert, it); insert.executeInsert() }
            insert.close()
        }
        checkForeignKeys(db)
    }

    private fun checkForeignKeys(db: SupportSQLiteDatabase) {
        db.query("PRAGMA foreign_key_check").use {
            if (it.moveToFirst()) {
                throw IllegalStateException("Foreign key violation in ${it.getString(0)} from sync")
            }
        }
    }
}

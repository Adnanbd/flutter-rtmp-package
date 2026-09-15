package com.flutterrtmp.broadcaster.overlay

/**
 * Minimal view of RootEncoder's GL filter list, so [LayerStack] is testable without GL.
 * Production: [GlFilterSink]. Ops are queued by RootEncoder and applied FIFO on the GL thread,
 * so indices computed sequentially on the calling thread stay consistent.
 */
interface FilterSink<F : Any> {
    fun add(index: Int, filter: F)
    fun remove(filter: F)
    fun clear()
}

/**
 * Single ordered stack of every overlay GL layer (ADR 0014, docs/specs/dynamic-overlays.md §3).
 *
 * Back → front order by [Key]: `(weight asc, classRank asc, seq asc)`.
 * An entry is either **attached** (has a live filter in the sink) or **detached** (hidden, keeps its key).
 *
 * Filters are always created through the entry's factory when (re)added — a filter object is never
 * re-added after removal, because RootEncoder may release GL resources on remove/clear.
 *
 * Not thread-safe: call from the main thread only.
 */
class LayerStack<F : Any>(private val sink: FilterSink<F>) {

    enum class LayerClass(val rank: Int) { SPONSOR(0), SCOREBAND(1), DYNAMIC(2) }

    data class Key(val weight: Int, val layerClass: LayerClass, val seq: Long) : Comparable<Key> {
        override fun compareTo(other: Key): Int =
            compareValuesBy(this, other, { it.weight }, { it.layerClass.rank }, { it.seq })
    }

    private class Entry<F : Any>(val id: String, var key: Key, var factory: () -> F, var filter: F?)

    private val entries = ArrayList<Entry<F>>()

    val attachedCount: Int get() = entries.count { it.filter != null }

    fun contains(id: String): Boolean = find(id) != null

    fun isAttached(id: String): Boolean = find(id)?.filter != null

    fun filterOf(id: String): F? = find(id)?.filter

    /** Ids back → front, attached and detached. */
    fun orderedIds(): List<String> = entries.map { it.id }

    /** Ids back → front of attached layers — mirrors the sink order. */
    fun attachedIds(): List<String> = entries.filter { it.filter != null }.map { it.id }

    /**
     * Insert a new layer. When [attached], its filter is created and added at the right index.
     * @return the created filter, or null when detached.
     */
    fun put(id: String, weight: Int, layerClass: LayerClass, seq: Long, attached: Boolean, factory: () -> F): F? {
        require(find(id) == null) { "layer '$id' already exists" }
        val entry = Entry(id, Key(weight.coerceIn(0, 100), layerClass, seq), factory, null)
        entries.add(insertionPoint(entry.key), entry)
        return if (attached) attachEntry(entry) else null
    }

    /** Replace how the layer's filter is built (next attach/rebuild/replace uses it). */
    fun setFactory(id: String, factory: () -> F) {
        entry(id).factory = factory
    }

    /** Swap an attached layer's filter for a freshly built one at the same index. No-op if detached. */
    fun replaceFilter(id: String): F? {
        val e = entry(id)
        val old = e.filter ?: return null
        val index = attachedIndexOf(e)
        sink.remove(old)
        val fresh = e.factory()
        sink.add(index, fresh)
        e.filter = fresh
        return fresh
    }

    /** Re-add a detached layer at its ordered index. Returns the filter (existing if already attached). */
    fun attach(id: String): F = entry(id).let { it.filter ?: attachEntry(it) }

    /** Remove the layer's filter from the sink but keep its slot. */
    fun detach(id: String) {
        val e = entry(id)
        e.filter?.let { sink.remove(it) }
        e.filter = null
    }

    fun remove(id: String) {
        val e = find(id) ?: return
        e.filter?.let { sink.remove(it) }
        entries.remove(e)
    }

    fun setWeight(id: String, weight: Int) {
        val e = entry(id)
        val newKey = e.key.copy(weight = weight.coerceIn(0, 100))
        if (newKey == e.key) return
        val wasAttached = e.filter != null
        e.filter?.let { sink.remove(it) }
        e.filter = null
        entries.remove(e)
        e.key = newKey
        entries.add(insertionPoint(newKey), e)
        if (wasAttached) attachEntry(e)
    }

    /** Clear the sink and re-add every attached layer in order with fresh filters. */
    fun rebuild() {
        sink.clear()
        var index = 0
        for (e in entries) {
            if (e.filter == null) continue
            val fresh = e.factory()
            sink.add(index++, fresh)
            e.filter = fresh
        }
    }

    /** Drop everything, sink included. */
    fun clear() {
        sink.clear()
        entries.clear()
    }

    private fun attachEntry(e: Entry<F>): F {
        val filter = e.factory()
        sink.add(attachedIndexOf(e), filter)
        e.filter = filter
        return filter
    }

    private fun attachedIndexOf(e: Entry<F>): Int {
        var n = 0
        for (other in entries) {
            if (other === e) return n
            if (other.filter != null) n++
        }
        error("entry '${e.id}' not in stack")
    }

    /** First position whose key is greater than [key] — equal keys keep insertion order. */
    private fun insertionPoint(key: Key): Int {
        val i = entries.indexOfFirst { it.key > key }
        return if (i < 0) entries.size else i
    }

    private fun find(id: String): Entry<F>? = entries.firstOrNull { it.id == id }

    private fun entry(id: String): Entry<F> = find(id) ?: throw NoSuchElementException("layer '$id' not found")
}

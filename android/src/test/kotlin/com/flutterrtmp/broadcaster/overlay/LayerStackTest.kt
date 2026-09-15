package com.flutterrtmp.broadcaster.overlay

import com.flutterrtmp.broadcaster.overlay.LayerStack.LayerClass.DYNAMIC
import com.flutterrtmp.broadcaster.overlay.LayerStack.LayerClass.SCOREBAND
import com.flutterrtmp.broadcaster.overlay.LayerStack.LayerClass.SPONSOR
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/** Simulates RootEncoder's filter list: a plain list mutated by index, like MainRender. */
private class FakeSink : FilterSink<FakeSink.Filter> {
    class Filter(val id: String, val gen: Int) {
        override fun toString() = "$id#$gen"
    }

    val filters = mutableListOf<Filter>()
    var clears = 0

    override fun add(index: Int, filter: Filter) {
        require(index in 0..filters.size) { "index $index out of 0..${filters.size}" }
        filters.add(index, filter)
    }

    override fun remove(filter: Filter) {
        require(filters.remove(filter)) { "remove of absent $filter" }
    }

    override fun clear() {
        filters.clear()
        clears++
    }

    fun order() = filters.map { it.id }
}

internal class LayerStackTest {

    private lateinit var sink: FakeSink
    private lateinit var stack: LayerStack<FakeSink.Filter>
    private val gens = mutableMapOf<String, Int>()

    private fun factory(id: String): () -> FakeSink.Filter = {
        val g = (gens[id] ?: 0) + 1
        gens[id] = g
        FakeSink.Filter(id, g)
    }

    private fun put(id: String, weight: Int, cls: LayerStack.LayerClass, seq: Long, attached: Boolean = true) =
        stack.put(id, weight, cls, seq, attached, factory(id))

    @BeforeTest
    fun setUp() {
        sink = FakeSink()
        stack = LayerStack(sink)
        gens.clear()
    }

    @Test
    fun defaultWeights_reproduceLegacyOrder_evenWhenScorebandAddedLast() {
        put("sponsor_0", 10, SPONSOR, 0)
        put("sponsor_1", 10, SPONSOR, 1)
        put("dyn_a", 50, DYNAMIC, 0)
        put("scoreband", 50, SCOREBAND, 0)   // lazy scoreband after a dynamic overlay
        put("dyn_b", 50, DYNAMIC, 1)
        assertEquals(listOf("sponsor_0", "sponsor_1", "scoreband", "dyn_a", "dyn_b"), sink.order())
        assertEquals(sink.order(), stack.attachedIds())
    }

    @Test
    fun weights_orderBackToFront() {
        put("front", 100, DYNAMIC, 0)
        put("back", 0, DYNAMIC, 1)
        put("mid", 50, DYNAMIC, 2)
        put("sponsor_0", 60, SPONSOR, 0)
        assertEquals(listOf("back", "mid", "sponsor_0", "front"), sink.order())
    }

    @Test
    fun detachedLayers_keepSlot_andReattachAtCorrectIndex() {
        put("a", 10, DYNAMIC, 0)
        put("b", 20, DYNAMIC, 1)
        put("c", 30, DYNAMIC, 2)
        stack.detach("b")
        assertEquals(listOf("a", "c"), sink.order())
        assertFalse(stack.isAttached("b"))
        assertEquals(listOf("a", "b", "c"), stack.orderedIds())
        stack.attach("b")
        assertEquals(listOf("a", "b", "c"), sink.order())
    }

    @Test
    fun putDetached_doesNotTouchSink() {
        put("a", 10, DYNAMIC, 0)
        put("hidden", 5, DYNAMIC, 1, attached = false)
        assertEquals(listOf("a"), sink.order())
        stack.attach("hidden")
        assertEquals(listOf("hidden", "a"), sink.order())
    }

    @Test
    fun setWeight_reordersWithFreshFilter() {
        put("a", 10, DYNAMIC, 0)
        put("b", 20, DYNAMIC, 1)
        put("c", 30, DYNAMIC, 2)
        val before = stack.filterOf("a")
        stack.setWeight("a", 25)
        assertEquals(listOf("b", "a", "c"), sink.order())
        assertNotSame(before, stack.filterOf("a"))
        stack.setWeight("c", 0)
        assertEquals(listOf("c", "b", "a"), sink.order())
    }

    @Test
    fun setWeight_onDetachedLayer_movesSlotWithoutAttaching() {
        put("a", 10, DYNAMIC, 0)
        put("b", 20, DYNAMIC, 1, attached = false)
        stack.setWeight("b", 0)
        assertEquals(listOf("a"), sink.order())
        assertEquals(listOf("b", "a"), stack.orderedIds())
    }

    @Test
    fun setWeight_sameValue_isNoOp() {
        put("a", 10, DYNAMIC, 0)
        val f = stack.filterOf("a")
        stack.setWeight("a", 10)
        assertTrue(f === stack.filterOf("a"))
    }

    @Test
    fun equalKeysAfterWeightChange_keepSeqOrder() {
        put("x", 50, DYNAMIC, 5)
        put("y", 40, DYNAMIC, 9)
        stack.setWeight("y", 50)    // y seq 9 > x seq 5 → y in front
        assertEquals(listOf("x", "y"), sink.order())
    }

    @Test
    fun replaceFilter_swapsInPlace() {
        put("a", 10, DYNAMIC, 0)
        put("b", 20, DYNAMIC, 1)
        put("c", 30, DYNAMIC, 2)
        stack.replaceFilter("b")
        assertEquals(listOf("a", "b", "c"), sink.order())
        assertEquals(2, sink.filters[1].gen)
    }

    @Test
    fun replaceFilter_onDetached_isNoOp() {
        put("a", 10, DYNAMIC, 0, attached = false)
        assertEquals(null, stack.replaceFilter("a"))
        assertEquals(emptyList(), sink.order())
    }

    @Test
    fun remove_dropsLayer() {
        put("a", 10, DYNAMIC, 0)
        put("b", 20, DYNAMIC, 1)
        stack.remove("a")
        assertEquals(listOf("b"), sink.order())
        assertFalse(stack.contains("a"))
        stack.remove("missing") // no throw
    }

    @Test
    fun rebuild_clearsSinkAndReaddsAttachedInOrderWithFreshFilters() {
        put("sponsor_0", 10, SPONSOR, 0)
        put("hidden", 20, DYNAMIC, 0, attached = false)
        put("scoreband", 50, SCOREBAND, 0)
        sink.filters.clear() // simulate GL pipeline dropping filters
        stack.rebuild()
        assertEquals(listOf("sponsor_0", "scoreband"), sink.order())
        assertEquals(1, sink.clears)
        assertTrue(sink.filters.all { it.gen == 2 })
    }

    @Test
    fun setFactory_usedOnNextReplace() {
        put("a", 10, DYNAMIC, 0)
        stack.setFactory("a") { FakeSink.Filter("a-new", 1) }
        stack.replaceFilter("a")
        assertEquals(listOf("a-new"), sink.order())
    }

    @Test
    fun duplicateId_andUnknownId_fail() {
        put("a", 10, DYNAMIC, 0)
        assertFailsWith<IllegalArgumentException> { put("a", 10, DYNAMIC, 1) }
        assertFailsWith<NoSuchElementException> { stack.detach("nope") }
        assertFailsWith<NoSuchElementException> { stack.setWeight("nope", 1) }
    }

    @Test
    fun weight_isClamped() {
        put("a", 500, DYNAMIC, 0)
        put("b", -3, DYNAMIC, 1)
        put("c", 100, DYNAMIC, 2)
        assertEquals(listOf("b", "a", "c"), sink.order()) // a clamped to 100, seq 0 < c seq 2
    }

    @Test
    fun clear_emptiesEverything() {
        put("a", 10, DYNAMIC, 0)
        stack.clear()
        assertEquals(0, stack.attachedCount)
        assertEquals(emptyList(), stack.orderedIds())
        assertEquals(emptyList(), sink.order())
    }
}

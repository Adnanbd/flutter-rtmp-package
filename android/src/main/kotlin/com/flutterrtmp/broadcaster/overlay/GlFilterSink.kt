package com.flutterrtmp.broadcaster.overlay

import com.pedro.encoder.input.gl.render.filters.BaseFilterRender
import com.pedro.library.generic.GenericStream

/** [FilterSink] backed by RootEncoder's `GlStreamInterface`. All overlay filter mutations go through [LayerStack]. */
class GlFilterSink(private val stream: GenericStream) : FilterSink<BaseFilterRender> {
    override fun add(index: Int, filter: BaseFilterRender) = stream.getGlInterface().addFilter(index, filter)
    override fun remove(filter: BaseFilterRender) = stream.getGlInterface().removeFilter(filter)
    override fun clear() = stream.getGlInterface().clearFilters()
    val glFiltersCount: Int get() = stream.getGlInterface().filtersCount()
}

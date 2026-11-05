package ua.komora.ui

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/** Added apps' pictures: a counter that changes whenever one is saved or all are removed, so icons are read again. */
object SourcePictures {
    /** The key an added app's icon is kept under, beside its screenshots' languages. */
    const val ICON = "icon"

    private val _version = MutableStateFlow(0)
    val version: StateFlow<Int> = _version

    fun changed() = _version.update { it + 1 }
}

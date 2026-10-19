package ru.suslikovanora.media.desktop

import ru.suslikovanora.media.core.*

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * «Это мультфильм» по данным TMDB (поле `animation` ответа /api/poster, жанр Animation), по нижнему регистру запроса постера.
 * Заполняет Library: сразу из кэша на диске, затем в фоне для фильмов, которых там ещё нет (Library.classifyAnimation).
 */
object Animation {
    val flags = mutableStateMapOf<String, Boolean>()
    /** Растёт при каждой порции новых ответов: от него пересчитывается фильтр категорий. */
    var version by mutableStateOf(0)

    fun of(item: LibraryItem): Boolean = item.posterQueries.any { flags[it.lowercase()] == true }
}

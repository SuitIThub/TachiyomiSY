package eu.kanade.presentation.browse.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.paging.compose.LazyPagingItems
import exh.metadata.metadata.RaisedSearchMetadata
import kotlinx.coroutines.flow.StateFlow
import tachiyomi.domain.manga.model.Manga
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.material.padding
import tachiyomi.presentation.core.i18n.stringResource

/**
 * Index of the manga item before which the "last visit" marker should appear,
 * or null if the marker should not be shown yet.
 */
fun lastVisitMarkerIndex(
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    lastSeenUrl: String?,
): Int? {
    if (lastSeenUrl.isNullOrBlank() || mangaList.itemCount == 0) return null
    for (index in 0 until mangaList.itemCount) {
        val url = mangaList.peek(index)?.value?.first?.url ?: continue
        if (url == lastSeenUrl) return index
    }
    return null
}

@Composable
fun BrowseSourceLastVisitHeader(
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = MaterialTheme.padding.small),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        HorizontalDivider(modifier = Modifier.weight(1f))
        Text(
            text = stringResource(SYMR.strings.browse_feed_last_visit),
            modifier = Modifier.padding(horizontal = MaterialTheme.padding.small),
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.SemiBold,
            style = MaterialTheme.typography.labelMedium,
        )
        HorizontalDivider(modifier = Modifier.weight(1f))
    }
}

@Composable
fun rememberLastVisitMarkerIndex(
    mangaList: LazyPagingItems<StateFlow<Pair<Manga, RaisedSearchMetadata?>>>,
    lastSeenUrl: String?,
): Int? {
    return remember(mangaList.itemCount, lastSeenUrl, mangaList.loadState) {
        lastVisitMarkerIndex(mangaList, lastSeenUrl)
    }
}

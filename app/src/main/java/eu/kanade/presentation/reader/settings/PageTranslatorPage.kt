package eu.kanade.presentation.reader.settings

import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import eu.kanade.tachiyomi.data.translator.PageTranslatorPreferences
import eu.kanade.tachiyomi.ui.reader.setting.ReaderSettingsScreenModel
import tachiyomi.i18n.MR
import tachiyomi.i18n.sy.SYMR
import tachiyomi.presentation.core.components.HeadingItem
import tachiyomi.presentation.core.components.SettingsChipRow
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.collectAsState
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get

@Composable
internal fun ColumnScope.PageTranslatorPage(screenModel: ReaderSettingsScreenModel) {
    val prefs = remember { Injekt.get<PageTranslatorPreferences>() }
    val manga by screenModel.mangaFlow.collectAsState()
    val mangaId = manga?.id ?: return

    HeadingItem(MR.strings.pref_category_for_this_series)

    val modePref = remember(mangaId) { prefs.mangaMode(mangaId) }
    val mode by modePref.collectAsState()
    SettingsChipRow(SYMR.strings.pref_page_translator_manga_mode) {
        PageTranslatorPreferences.MangaTranslatorMode.entries.forEach { entry ->
            FilterChip(
                selected = entry == mode,
                onClick = { modePref.set(entry) },
                label = {
                    Text(
                        stringResource(
                            when (entry) {
                                PageTranslatorPreferences.MangaTranslatorMode.DEFAULT ->
                                    SYMR.strings.pref_page_translator_manga_mode_default
                                PageTranslatorPreferences.MangaTranslatorMode.ON ->
                                    SYMR.strings.pref_page_translator_manga_mode_on
                                PageTranslatorPreferences.MangaTranslatorMode.OFF ->
                                    SYMR.strings.pref_page_translator_manga_mode_off
                            },
                        ),
                    )
                },
            )
        }
    }

    val sourcePref = remember(mangaId) { prefs.mangaSourceLanguage(mangaId) }
    val sourceLang by sourcePref.collectAsState()
    SettingsChipRow(SYMR.strings.pref_page_translator_source_lang) {
        PageTranslatorPreferences.TranslatorSourceLanguage.entries.forEach { entry ->
            FilterChip(
                selected = entry == sourceLang,
                onClick = { sourcePref.set(entry) },
                label = {
                    Text(
                        when (entry) {
                            PageTranslatorPreferences.TranslatorSourceLanguage.DEFAULT ->
                                stringResource(SYMR.strings.pref_page_translator_lang_default)
                            PageTranslatorPreferences.TranslatorSourceLanguage.AUTO ->
                                stringResource(SYMR.strings.pref_page_translator_lang_auto)
                            else -> entry.code ?: entry.name
                        },
                    )
                },
            )
        }
    }

    val targetPref = remember(mangaId) { prefs.mangaTargetLanguage(mangaId) }
    val targetLang by targetPref.collectAsState()
    SettingsChipRow(SYMR.strings.pref_page_translator_target_lang) {
        PageTranslatorPreferences.TranslatorTargetLanguage.entries.forEach { entry ->
            FilterChip(
                selected = entry == targetLang,
                onClick = { targetPref.set(entry) },
                label = {
                    Text(
                        if (entry == PageTranslatorPreferences.TranslatorTargetLanguage.DEFAULT) {
                            stringResource(SYMR.strings.pref_page_translator_lang_default)
                        } else {
                            entry.code
                        },
                    )
                },
            )
        }
    }

    HeadingItem(SYMR.strings.pref_category_page_translator)
    val globalEnabled by prefs.enabled.collectAsState()
    val provider by prefs.provider.collectAsState()
    Text(
        text = stringResource(SYMR.strings.pref_page_translator_info) +
            "\n" +
            stringResource(SYMR.strings.pref_page_translator_provider) + ": " +
            when (provider) {
                PageTranslatorPreferences.TranslatorProvider.AUTO ->
                    stringResource(SYMR.strings.pref_page_translator_provider_auto)
                PageTranslatorPreferences.TranslatorProvider.ON_DEVICE ->
                    stringResource(SYMR.strings.pref_page_translator_provider_on_device)
                PageTranslatorPreferences.TranslatorProvider.DEEPL ->
                    stringResource(SYMR.strings.pref_page_translator_provider_deepl)
            } +
            if (globalEnabled) {
                " · " + stringResource(SYMR.strings.pref_page_translator_manga_mode_on)
            } else {
                " · " + stringResource(SYMR.strings.pref_page_translator_manga_mode_off)
            },
    )
}

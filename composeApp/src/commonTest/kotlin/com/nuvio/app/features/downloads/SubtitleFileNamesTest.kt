package com.nuvio.app.features.downloads

import kotlin.test.Test
import kotlin.test.assertEquals

class SubtitleFileNamesTest {
    private val video = "Кафе «У Белого Медведя» S01E10.mkv"

    private fun source(label: String?, extension: String = "ass") = SubtitleSource(
        sourceUrl = "unused",
        language = "rus",
        name = label,
        label = label,
        extension = extension,
        content = SubtitleContent.Text(""),
    )

    @Test
    fun singleSubtitleTakesTheVideoNameWithoutALabel() {
        assertEquals(
            listOf("Кафе «У Белого Медведя» S01E10.ass"),
            subtitleFileNames(video, listOf(source("AniLibria"))),
        )
    }

    @Test
    fun severalSubtitlesCarryTheirPackLabels() {
        assertEquals(
            listOf(
                "Кафе «У Белого Медведя» S01E10.AniLibria.ass",
                "Кафе «У Белого Медведя» S01E10.Shojosei.ass",
            ),
            subtitleFileNames(video, listOf(source("AniLibria"), source("Shojosei"))),
        )
    }

    @Test
    fun missingOrSharedLabelsFallBackToPosition() {
        assertEquals(
            listOf(
                "Кафе «У Белого Медведя» S01E10.1.ass",
                "Кафе «У Белого Медведя» S01E10.2.ass",
                "Кафе «У Белого Медведя» S01E10.3.srt",
            ),
            subtitleFileNames(video, listOf(source("Group"), source("Group"), source(null, "srt"))),
        )
    }

    @Test
    fun labelsLoseDotsAndCharactersFilesCannotHold() {
        assertEquals(
            listOf(
                "Кафе «У Белого Медведя» S01E10.Sub Group - Team.ass",
                "Кафе «У Белого Медведя» S01E10.A-B.ass",
            ),
            subtitleFileNames(video, listOf(source("Sub.Group: Team"), source("A/B"))),
        )
    }
}

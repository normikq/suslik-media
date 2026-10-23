package ru.suslikovanora.media.core

import org.json.JSONObject
import ru.suslikovanora.media.core.audio.CustomAudio
import ru.suslikovanora.media.core.audio.CustomAudioIndex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CustomAudioTest {
    private val url = "https://media.test/api/file/proxy/Film.rus.mka?link=https%3A%2F%2Falldebrid.com%2Ff%2Fa&exp=1000&sig=ff"
    private val list = JSONObject("""{"tracks": [
        {"id": "t1", "videoPath": "Film/Film.mkv", "audioPath": "Dub/HDRezka.mka", "title": "HDRezka [rus]", "lang": "rus",
         "offsetMs": -5000, "mine": true, "url": "$url", "audioMagnetId": 7},
        {"id": "t2", "videoPath": "Film/Film.mkv", "audioPath": "Dub/Other.MKA", "title": "", "lang": "und", "url": "https://x/Other.MKA"},
        {"id": "t4", "videoPath": "Film/Film.mkv", "audioPath": "Dub/Bare.aac", "url": "https://x/Bare.aac"},
        {"id": "t3", "videoPath": "Film/Film.mkv", "audioPath": "Dub/Broken.mka", "url": ""}
    ]}""")

    @Test fun readsTracks() {
        val t = CustomAudio.parseTracks(list)
        assertEquals(listOf("t1", "t2"), t.map { it.id })          // without a link or not .mka — left out
        assertEquals("HDRezka [rus]", t[0].menuName)
        assertEquals(7L, t[0].audioMagnetId)
        assertNull(t[1].lang)                                      // "und" is no language
        assertEquals("Other.MKA", t[1].menuName)                   // no title — the file name
        assertEquals("HDRezka [rus]", t[0].copy(title = "HDRezka [RUS]").menuName) // tag case does not repeat the language
    }

    @Test fun findsByVideoAndMenuName() {
        val t = CustomAudio.parseTracks(list)
        assertEquals(2, CustomAudio.forVideo(t, "12|Film/Film.mkv").size)
        assertEquals(0, CustomAudio.forVideo(t, "12|Film/Other.mkv").size)
        assertEquals(0, CustomAudio.forVideo(t, null).size)
        assertEquals("t1", CustomAudio.byMenuName(t, "HDRezka [rus]")?.id)
        assertEquals("t2", CustomAudio.byMenuName(t, "Other.MKA [und]")?.id)
        assertNull(CustomAudio.byMenuName(t, "HDRezka"))
    }

    @Test fun readsCheckAndAttach() {
        val c = CustomAudio.parseCheck(JSONObject("""{"movieMagnetId": 12, "pairs": [
            {"videoPath": "v.mkv", "audioPath": "a.mka", "voice": "LostFilm", "season": 1, "episode": null, "offsetMs": null}]}"""))
        assertEquals(12L, c.movieMagnetId)
        assertEquals(true, c.attachable)
        assertEquals(1, c.pairs[0].season)
        assertNull(c.pairs[0].episode)
        assertNull(c.pairs[0].offsetMs)
        val a = CustomAudio.parseAttached(JSONObject("""{"created": [{"id": "a"}], "existing": [{"id": "b"}]}"""))
        assertEquals(listOf("a", "b"), a.map { it.getString("id") })
        assertEquals(listOf(false, true), a.map { it.optBoolean("existing") })
    }

    @Test fun indexFindsByFileAndWrappedAddress() {
        val idx = CustomAudioIndex()
        idx.read(CustomAudio.infoJson(CustomAudio.parseTracks(list)))
        val fresh = url.replace("exp=1000&sig=ff", "exp=2000&sig=aa")  // a new signature — the same file
        assertEquals(-5000L, idx.of(fresh)?.offsetMs)
        assertEquals("HDRezka [rus]", idx.of(fresh)?.title)
        idx.noteWrapped(url, "http://127.0.0.1:5000/s/1")
        assertEquals(-5000L, idx.of("http://127.0.0.1:5000/s/1")?.offsetMs)
        assertNull(CustomAudio.infoJson(emptyList()))
    }

    @Test fun voiceOverOnlyAllowsSideFiles() {
        assertEquals(true, CustomAudio.isVoiceOverOnly(listOf("Dub/a.mka", "Dub/readme.txt", "Dub/cover.JPG")))
        assertEquals(false, CustomAudio.isVoiceOverOnly(listOf("Dub/readme.txt")))          // no voice-over at all
        assertEquals(false, CustomAudio.isVoiceOverOnly(listOf("Film/a.mka", "Film/Film.mkv"))) // a film with a dub beside it
    }
}

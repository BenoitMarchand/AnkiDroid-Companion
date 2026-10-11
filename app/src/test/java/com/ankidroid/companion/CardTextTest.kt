package com.ankidroid.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [CardText.clean] uses Android's HTML parser, so only the pure parts are tested here. */
class CardTextTest {

    @Test
    fun detectsSoundTags() {
        assertTrue(CardText.hasSound("[sound:hello.mp3]"))
        assertTrue(CardText.hasSound("Listen: [anki:play:q:0]"))
        assertTrue(CardText.hasSound("<div>[anki:play:a:1]</div>"))
    }

    @Test
    fun textWithoutSoundIsNotSkipped() {
        assertFalse(CardText.hasSound("Bonjour"))
        assertFalse(CardText.hasSound("[[type:Back]]"))
        assertFalse(CardText.hasSound("<img src=\"a.png\">"))
        assertFalse(CardText.hasSound(null))
    }

    @Test
    fun stripsSoundAndTypedAnswerTags() {
        assertEquals("Hello  world", CardText.stripMediaTags("Hello [sound:a.mp3] world"))
        assertEquals("Q: ", CardText.stripMediaTags("Q: [anki:play:q:0]"))
        assertEquals("Translate\n\n", CardText.stripMediaTags("Translate\n\n[[type:Verso]]"))
    }

    @Test
    fun soundOnlyQuestionLeavesNoText() {
        assertEquals("", CardText.stripMediaTags("[anki:play:q:0]\n\n[[type:Verso]]").trim())
    }

    @Test
    fun keepsRegularBrackets() {
        assertEquals("[note] a [b]", CardText.stripMediaTags("[note] a [b]"))
    }
}

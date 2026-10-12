package com.ankidroid.companion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DeckTreeTest {

    private fun deck(id: Long, name: String) = DeckItem(id, name, newCount = 0, learnCount = 0, reviewCount = 0)

    private val decks = listOf(
        deck(1, "Languages"),
        deck(2, "Languages::Spanish"),
        deck(3, "Languages::Spanish::Verbs"),
        deck(4, "languages::German"),
        deck(5, "Default"),
        deck(6, "Languages::English"),
    )

    @Test
    fun buildsTheHierarchyFromDeckNames() {
        val roots = buildDeckTree(decks)

        assertEquals(listOf("Default", "Languages", "languages"), roots.map { it.fullName })
        val languages = roots[1]
        assertEquals(1L, languages.deck?.id)
        assertEquals(listOf("English", "Spanish"), languages.children.map { it.shortName })
        assertEquals(listOf("Verbs"), languages.children[1].children.map { it.shortName })
        assertEquals(2, languages.children[1].children[0].depth)
    }

    @Test
    fun createsMissingParents() {
        // "languages" (lower case) is not a listed deck: it's only the parent of "languages::German".
        val missingParent = buildDeckTree(decks).single { it.fullName == "languages" }

        assertNull(missingParent.deck)
        assertEquals(listOf("German"), missingParent.children.map { it.shortName })
    }

    @Test
    fun foldedTreeShowsOnlyTopLevelDecks() {
        val visible = flattenVisible(buildDeckTree(decks), expanded = emptySet())

        assertEquals(listOf("Default", "Languages", "languages"), visible.map { it.fullName })
    }

    @Test
    fun unfoldingShowsChildrenOfExpandedDecksOnly() {
        val visible = flattenVisible(buildDeckTree(decks), expanded = setOf("Languages"))

        assertEquals(
            listOf("Default", "Languages", "Languages::English", "Languages::Spanish", "languages"),
            visible.map { it.fullName },
        )
    }

    @Test
    fun unfoldingNestedDecks() {
        val visible = flattenVisible(buildDeckTree(decks), expanded = setOf("Languages", "Languages::Spanish"))

        assertEquals(
            listOf("Default", "Languages", "Languages::English", "Languages::Spanish", "Languages::Spanish::Verbs", "languages"),
            visible.map { it.fullName },
        )
    }

    @Test
    fun subdecksOfASelectedDeckAreIncludedInIt() {
        val selected = setOf("Languages")

        assertEquals("Languages", selectedAncestor("Languages::Spanish", selected))
        assertEquals("Languages", selectedAncestor("Languages::Spanish::Verbs", selected))
        assertNull(selectedAncestor("Languages", selected))
        assertNull(selectedAncestor("Default", selected))
        assertNull(selectedAncestor("languages::German", selected))
    }

    @Test
    fun nearestSelectedAncestorWins() {
        val selected = setOf("Languages", "Languages::Spanish")

        assertEquals("Languages::Spanish", selectedAncestor("Languages::Spanish::Verbs", selected))
    }

    @Test
    fun deckItemSplitsNameAndParentPath() {
        assertEquals("Verbs", deck(3, "Languages::Spanish::Verbs").shortName)
        assertEquals("Languages › Spanish", deck(3, "Languages::Spanish::Verbs").parentPath)
        assertNull(deck(5, "Default").parentPath)
    }
}

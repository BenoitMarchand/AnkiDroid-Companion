package com.ankidroid.companion.ui

/** A deck in the tree. [deck] is null for a parent that AnkiDroid didn't list (names are just paths). */
internal class DeckNode(val fullName: String, val depth: Int) {
    var deck: DeckItem? = null
    val children = mutableListOf<DeckNode>()
    val shortName: String get() = fullName.substringAfterLast("::")
}

/** Builds the deck hierarchy from AnkiDroid's "Parent::Child" names, sorted alphabetically at each level. */
internal fun buildDeckTree(decks: List<DeckItem>): List<DeckNode> {
    val nodes = HashMap<String, DeckNode>()
    val roots = mutableListOf<DeckNode>()

    fun nodeFor(fullName: String): DeckNode = nodes.getOrPut(fullName) {
        val parentName = fullName.substringBeforeLast("::", "")
        val node = DeckNode(fullName, depth = fullName.split("::").size - 1)
        if (parentName.isEmpty()) roots.add(node) else nodeFor(parentName).children.add(node)
        node
    }

    decks.forEach { nodeFor(it.name).deck = it }
    fun sort(list: MutableList<DeckNode>) {
        list.sortBy { it.shortName.lowercase() }
        list.forEach { sort(it.children) }
    }
    sort(roots)
    return roots
}

/**
 * The nearest selected ancestor of the deck [fullName], or null. Studying a deck includes its subdecks, so a
 * deck under a selected deck is already reviewed through it.
 */
internal fun selectedAncestor(fullName: String, selectedNames: Set<String>): String? {
    var name = fullName.substringBeforeLast("::", "")
    while (name.isNotEmpty()) {
        if (name in selectedNames) return name
        name = name.substringBeforeLast("::", "")
    }
    return null
}

internal fun flattenVisible(nodes: List<DeckNode>, expanded: Set<String>): List<DeckNode> = buildList {
    fun visit(list: List<DeckNode>) {
        list.forEach { node ->
            add(node)
            if (node.fullName in expanded) visit(node.children)
        }
    }
    visit(nodes)
}

package kotbrowse

/** A node of the document tree: either an element or a run of text. */
sealed class Node {
    var parent: Element? = null
    val children = mutableListOf<Node>()
}

class Element(val tag: String, val attrs: Map<String, String> = emptyMap()) : Node() {
    val id: String? get() = attrs["id"]
    val classes: Set<String>
        get() = attrs["class"]?.split(' ', '\t', '\n', '\r')?.filter { it.isNotEmpty() }?.toSet() ?: emptySet()

    fun append(node: Node) {
        node.parent = this
        children.add(node)
    }

    /** This element and every descendant element, in document order. */
    fun elements(): Sequence<Element> = sequence {
        yield(this@Element)
        for (c in children) if (c is Element) yieldAll(c.elements())
    }

    fun find(tag: String): Element? = elements().firstOrNull { it.tag == tag }

    fun textContent(): String = children.joinToString("") {
        when (it) {
            is Text -> it.text
            is Element -> it.textContent()
        }
    }

    override fun toString(): String = "<$tag" + attrs.entries.joinToString("") { " ${it.key}=\"${it.value}\"" } + ">"
}

class Text(val text: String) : Node() {
    override fun toString(): String = "\"$text\""
}

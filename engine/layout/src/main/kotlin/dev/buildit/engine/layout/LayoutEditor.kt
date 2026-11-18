package dev.buildit.engine.layout

/** A change to a layout document. Commands are pure: they return a new root or throw [LayoutEditException]. */
sealed interface LayoutCommand {
    data class Insert(val parentUid: Long, val index: Int, val tag: String, val attributes: Map<String, String> = emptyMap()) : LayoutCommand
    data class Move(val uid: Long, val newParentUid: Long, val index: Int) : LayoutCommand
    data class Remove(val uid: Long) : LayoutCommand
    data class SetAttribute(val uid: Long, val name: String, val value: String?) : LayoutCommand
    data class Duplicate(val uid: Long) : LayoutCommand
    /** Inserts a copy of [element] and its children (e.g. a widget from the collection), with fresh ids. */
    data class InsertTree(val parentUid: Long, val index: Int, val element: XmlElement) : LayoutCommand
}

class LayoutEditException(val code: String, message: String) : Exception(message)

data class LayoutDiagnostic(val uid: Long, val severity: Severity, val code: String, val detail: String = "") {
    enum class Severity { Error, Warning }
}

/**
 * Editing session for one layout file: applies [LayoutCommand]s, keeps bounded undo/redo history,
 * validates, and serializes. The document held here is the single source of truth for the designer
 * canvas, the component tree, the inspector and the XML written to disk.
 */
class LayoutEditor(initial: XmlElement, private val uids: UidSource = UidSource(initial.walk().maxOf { it.uid } + 1), private val historyLimit: Int = 100) {
    var root: XmlElement = initial
        private set
    private val undoStack = ArrayDeque<XmlElement>()
    private val redoStack = ArrayDeque<XmlElement>()

    /** uid of the element created by the last Insert/Duplicate, for auto-selection. */
    var lastCreatedUid: Long? = null
        private set

    val canUndo get() = undoStack.isNotEmpty()
    val canRedo get() = redoStack.isNotEmpty()

    fun apply(command: LayoutCommand) {
        lastCreatedUid = null
        val next = execute(root, command)
        if (next == root) return
        undoStack.addLast(root)
        if (undoStack.size > historyLimit) undoStack.removeFirst()
        redoStack.clear()
        root = next
    }

    fun undo(): Boolean {
        val prev = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(root)
        root = prev
        return true
    }

    fun redo(): Boolean {
        val next = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(root)
        root = next
        return true
    }

    fun toXml(): String = XmlWriter.write(root)

    private fun execute(root: XmlElement, c: LayoutCommand): XmlElement = when (c) {
        is LayoutCommand.Insert -> {
            val parent = root.find(c.parentUid) ?: throw LayoutEditException("no_parent", "Parent not found")
            checkCanAccept(parent, extra = 1)
            val def = ComponentCatalog.find(c.tag)
            val attrs = LinkedHashMap<String, String>()
            attrs[Attr.ID] = "@+id/" + uniqueId(root, def?.idPrefix ?: c.tag.substringAfterLast('.').replaceFirstChar { it.lowercase() })
            def?.defaults?.forEach { (k, v) -> attrs[k] = v }
            attrs.putAll(c.attributes)
            if (Attr.WIDTH !in attrs) attrs[Attr.WIDTH] = "wrap_content"
            if (Attr.HEIGHT !in attrs) attrs[Attr.HEIGHT] = "wrap_content"
            val el = XmlElement(uids.next(), c.tag, attrs)
            lastCreatedUid = el.uid
            ensureNamespaces(insertChild(root, c.parentUid, c.index, el), el)
        }
        is LayoutCommand.Move -> {
            if (c.uid == root.uid) throw LayoutEditException("move_root", "The root element can't be moved")
            val el = root.find(c.uid) ?: throw LayoutEditException("not_found", "Element not found")
            if (el.find(c.newParentUid) != null) throw LayoutEditException("move_into_self", "An element can't be moved inside itself")
            val newParent = root.find(c.newParentUid) ?: throw LayoutEditException("no_parent", "Parent not found")
            val oldParent = root.parentOf(c.uid)!!
            val sameParent = oldParent.uid == newParent.uid
            if (!sameParent) checkCanAccept(newParent, extra = 1)
            var index = c.index
            if (sameParent) {
                val oldIndex = oldParent.elements.indexOfFirst { it.uid == c.uid }
                if (oldIndex < index) index--
                if (oldIndex == index) return root
            }
            val without = removeChild(root, c.uid)
            insertChild(without, c.newParentUid, index, el)
        }
        is LayoutCommand.Remove -> {
            if (c.uid == root.uid) throw LayoutEditException("remove_root", "The root element can't be removed")
            root.find(c.uid) ?: throw LayoutEditException("not_found", "Element not found")
            removeChild(root, c.uid)
        }
        is LayoutCommand.SetAttribute -> {
            root.find(c.uid) ?: throw LayoutEditException("not_found", "Element not found")
            if (c.name == Attr.ID && c.value != null) validateIdChange(root, c.uid, c.value)
            val value = c.value?.takeIf { it.isNotEmpty() }
            if ((c.name == Attr.WIDTH || c.name == Attr.HEIGHT) && value == null) {
                throw LayoutEditException("size_required", "Width and height are required")
            }
            val updated = root.replace(c.uid) { it.withAttribute(c.name, value) }!!
            ensureNamespaces(updated, updated.find(c.uid)!!)
        }
        is LayoutCommand.InsertTree -> insertTree(root, c)
        is LayoutCommand.Duplicate -> {
            if (c.uid == root.uid) throw LayoutEditException("duplicate_root", "The root element can't be duplicated")
            val el = root.find(c.uid) ?: throw LayoutEditException("not_found", "Element not found")
            val parent = root.parentOf(c.uid)!!
            checkCanAccept(parent, extra = 1)
            val usedIds = allIds(root).toMutableSet()
            val copy = deepCopy(el, usedIds)
            lastCreatedUid = copy.uid
            insertChild(root, parent.uid, parent.elements.indexOfFirst { it.uid == c.uid } + 1, copy)
        }
    }

    private fun insertTree(root: XmlElement, c: LayoutCommand.InsertTree): XmlElement {
        val parent = root.find(c.parentUid) ?: throw LayoutEditException("no_parent", "Parent not found")
        checkCanAccept(parent, extra = 1)
        fun strip(e: XmlElement): XmlElement = e.copy(
            attributes = LinkedHashMap(e.attributes.filterKeys { !it.startsWith("xmlns") }),
            children = e.children.map { if (it is XmlElement) strip(it) else it },
        )
        val copy = deepCopy(strip(c.element), allIds(root).toMutableSet())
        lastCreatedUid = copy.uid
        return ensureNamespaces(insertChild(root, c.parentUid, c.index.coerceIn(0, parent.elements.size), copy), copy)
    }

    private fun checkCanAccept(parent: XmlElement, extra: Int) {
        val def = ComponentCatalog.find(parent.name)
        val container = def?.isContainer ?: parent.elements.isNotEmpty()
        if (!container) throw LayoutEditException("not_container", "${parent.name} can't contain other views")
        val max = def?.maxChildren
        if (max != null && parent.elements.size + extra > max) {
            throw LayoutEditException("too_many_children", "${def.displayName} can hold only $max child view")
        }
    }

    private fun deepCopy(e: XmlElement, usedIds: MutableSet<String>): XmlElement {
        val attrs = LinkedHashMap(e.attributes)
        e[Attr.ID]?.let { id ->
            val base = idName(id).trimEnd { it.isDigit() }.ifEmpty { "view" }
            var n = 1
            while ("$base$n" in usedIds) n++
            usedIds += "$base$n"
            attrs[Attr.ID] = "@+id/$base$n"
        }
        return XmlElement(uids.next(), e.name, attrs, e.children.map { if (it is XmlElement) deepCopy(it, usedIds) else it })
    }

    private fun validateIdChange(root: XmlElement, uid: Long, value: String) {
        if (value.isEmpty()) return
        val name = idName(value)
        if (!ID_PATTERN.matches(name)) throw LayoutEditException("invalid_id", "IDs use letters, digits and _ and start with a letter")
        if (root.walk().any { it.uid != uid && it[Attr.ID]?.let(::idName) == name }) {
            throw LayoutEditException("duplicate_id", "Another view already uses the id $name")
        }
    }

    companion object {
        val ID_PATTERN = Regex("[a-zA-Z][a-zA-Z0-9_]*")

        fun idName(value: String): String = value.substringAfter('/')

        fun allIds(root: XmlElement): Set<String> = root.walk().mapNotNull { it[Attr.ID]?.let(::idName) }.toSet()

        fun uniqueId(root: XmlElement, prefix: String): String {
            val used = allIds(root)
            var n = 1
            while ("$prefix$n" in used) n++
            return "$prefix$n"
        }

        fun insertChild(root: XmlElement, parentUid: Long, index: Int, child: XmlElement): XmlElement =
            root.replace(parentUid) { p ->
                // Index counts elements only; map it onto the mixed children list.
                val elementPositions = p.children.withIndex().filter { it.value is XmlElement }.map { it.index }
                val at = when {
                    index <= 0 -> elementPositions.firstOrNull() ?: p.children.size
                    index >= elementPositions.size -> (elementPositions.lastOrNull()?.plus(1)) ?: p.children.size
                    else -> elementPositions[index]
                }
                p.copy(children = p.children.toMutableList().apply { add(at, child) })
            }!!

        fun removeChild(root: XmlElement, uid: Long): XmlElement {
            val parent = root.parentOf(uid) ?: return root
            return root.replace(parent.uid) { p -> p.copy(children = p.children.filterNot { it is XmlElement && it.uid == uid }) }!!
        }

        /** Adds xmlns:app on the root when an app: attribute is used anywhere. */
        /** [element] and its children as a standalone XML document (for the collection). */
        fun subtreeXml(element: XmlElement): String {
            val attrs = LinkedHashMap<String, String>()
            attrs["xmlns:android"] = Attr.NS_ANDROID
            if (element.walk().any { e -> e.attributes.keys.any { it.startsWith("app:") } }) attrs["xmlns:app"] = Attr.NS_APP
            if (element.walk().any { e -> e.attributes.keys.any { it.startsWith("tools:") } }) attrs["xmlns:tools"] = Attr.NS_TOOLS
            element.attributes.filterKeys { !it.startsWith("xmlns") }.forEach { (k, v) -> attrs[k] = v }
            return XmlWriter.write(element.copy(attributes = attrs))
        }

        fun ensureNamespaces(root: XmlElement, changed: XmlElement): XmlElement {
            var r = root
            if (r["xmlns:android"] == null) r = r.withAttribute("xmlns:android", Attr.NS_ANDROID)
            val usesApp = changed.walk().any { e -> e.attributes.keys.any { it.startsWith("app:") } }
            if (usesApp && r["xmlns:app"] == null) r = r.withAttribute("xmlns:app", Attr.NS_APP)
            val usesTools = changed.walk().any { e -> e.attributes.keys.any { it.startsWith("tools:") } }
            if (usesTools && r["xmlns:tools"] == null) r = r.withAttribute("xmlns:tools", Attr.NS_TOOLS)
            return r
        }

        /** Diagnostics for the whole document (shown as badges and in the problems list). */
        fun validate(root: XmlElement): List<LayoutDiagnostic> = buildList {
            val seen = mutableMapOf<String, Long>()
            root.walk().forEach { e ->
                if (e[Attr.WIDTH] == null) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "missing_width"))
                if (e[Attr.HEIGHT] == null) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "missing_height"))
                e[Attr.ID]?.let { id ->
                    val n = idName(id)
                    if (!ID_PATTERN.matches(n)) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "invalid_id", n))
                    if (seen.put(n, e.uid) != null) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "duplicate_id", n))
                }
                val def = ComponentCatalog.find(e.name)
                if (def == null) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Warning, "unknown_view", e.name))
                if (def != null && !def.isContainer && e.elements.isNotEmpty()) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "children_not_allowed", def.displayName))
                if (def?.maxChildren != null && e.elements.size > def.maxChildren) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Error, "too_many_children", def.displayName))
                if (e.name == "ImageView" || e.name == "ImageButton") {
                    if (e["android:contentDescription"] == null) add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Warning, "missing_content_description"))
                }
                val parent = root.parentOf(e.uid)
                if (e["android:layout_weight"] != null && parent?.name != "LinearLayout") {
                    add(LayoutDiagnostic(e.uid, LayoutDiagnostic.Severity.Warning, "weight_outside_linear"))
                }
            }
        }

        /** New layout file content with the given root container. */
        fun newLayout(rootTag: String = "LinearLayout"): String {
            val def = ComponentCatalog.find(rootTag)
            val attrs = LinkedHashMap<String, String>()
            attrs["xmlns:android"] = Attr.NS_ANDROID
            attrs[Attr.WIDTH] = "match_parent"
            attrs[Attr.HEIGHT] = "match_parent"
            def?.defaults?.filterKeys { it != Attr.WIDTH && it != Attr.HEIGHT }?.forEach { (k, v) -> attrs[k] = v }
            return XmlWriter.write(XmlElement(0, rootTag, attrs))
        }
    }
}

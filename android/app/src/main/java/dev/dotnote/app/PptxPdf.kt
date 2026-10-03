package dev.dotnote.app

import android.graphics.*
import android.text.Layout
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.RelativeSizeSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.util.Xml
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.URI
import java.nio.ByteBuffer
import java.util.zip.ZipFile
import kotlin.math.roundToInt
import org.xmlpull.v1.XmlPullParser

/** A bounded, offline PresentationML renderer. No scripts, external relationships or network IO. */
internal object PptxPdf {
    private const val EMU = 12700f // DrawingML units per PDF point

    private data class Node(
        val name: String,
        val attrs: Map<String, String>,
        val children: MutableList<Node> = mutableListOf(),
        var text: String = "",
    ) {
        operator fun get(key: String) = attrs[key]

        fun child(name: String) = children.firstOrNull { it.name == name }

        fun descendants(name: String): List<Node> =
            children.flatMap {
                (if (it.name == name) listOf(it) else emptyList()) + it.descendants(name)
            }

        fun number(key: String, fallback: Float = 0f): Float =
            attrs[key]?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
    }

    private data class Relationship(val target: String, val type: String)

    private class Package(file: File) : AutoCloseable {
        private val zip = ZipFile(file)
        private var bytesRead = 0L
        private val readParts = mutableSetOf<String>()

        init {
            if (zip.size() > 10000) {
                zip.close()
                error("PowerPoint contains too many parts")
            }
        }

        fun bytes(path: String, limit: Long = 16L * 1024 * 1024): ByteArray {
            val entry = zip.getEntry(path) ?: error("PowerPoint is missing $path")
            require(entry.size <= limit) { "PowerPoint part is too large" }
            return zip.getInputStream(entry).use { input ->
                val out = ByteArrayOutputStream()
                DocumentImport.copyLimited(input, out, limit)
                // Shared backgrounds/media can be read on many slides. Count archive content once.
                if (readParts.add(path)) bytesRead += out.size()
                require(bytesRead <= 256L * 1024 * 1024) {
                    "PowerPoint exceeds the expanded import limit"
                }
                out.toByteArray()
            }
        }

        fun xml(path: String): Node {
            val parser = Xml.newPullParser()
            parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
            parser.setInput(bytes(path).inputStream(), null)
            val stack = mutableListOf<Node>()
            var root: Node? = null
            var count = 0
            while (true) {
                when (parser.nextToken()) {
                    XmlPullParser.DOCDECL ->
                        error("PowerPoint XML must not contain a document type")
                    XmlPullParser.START_TAG -> {
                        require(++count <= 100000 && stack.size < 128) {
                            "PowerPoint XML is too complex"
                        }
                        val node =
                            Node(
                                parser.name,
                                (0 until parser.attributeCount).associate {
                                    val prefix =
                                        if (
                                            parser
                                                .getAttributeNamespace(it)
                                                .endsWith("/relationships")
                                        )
                                            "r:"
                                        else ""
                                    prefix + parser.getAttributeName(it) to
                                        parser.getAttributeValue(it)
                                },
                            )
                        if (stack.isEmpty()) root = node else stack.last().children.add(node)
                        stack.add(node)
                    }
                    XmlPullParser.TEXT,
                    XmlPullParser.CDSECT,
                    XmlPullParser.ENTITY_REF ->
                        if (stack.isNotEmpty()) {
                            stack.last().text += parser.text.orEmpty()
                        }
                    XmlPullParser.END_TAG -> stack.removeAt(stack.lastIndex)
                    XmlPullParser.END_DOCUMENT -> return root ?: error("Empty PowerPoint XML")
                }
            }
        }

        fun relationships(part: String): Map<String, Relationship> {
            val directory = part.substringBeforeLast('/', "")
            val relPath =
                (if (directory.isEmpty()) "" else "$directory/") +
                    "_rels/" +
                    part.substringAfterLast('/') +
                    ".rels"
            if (zip.getEntry(relPath) == null) return emptyMap()
            return xml(relPath)
                .children
                .filter { it.name == "Relationship" && it["TargetMode"] != "External" }
                .associate { rel ->
                    val target =
                        URI(part)
                            .resolve(
                                rel["Target"] ?: error("Missing PowerPoint relationship target")
                            )
                            .normalize()
                    require(
                        !target.isAbsolute &&
                            target.rawAuthority == null &&
                            target.query == null &&
                            target.fragment == null
                    ) {
                        "Invalid PowerPoint relationship"
                    }
                    val path = target.path.removePrefix("/")
                    require(
                        path.isNotEmpty() && path.split('/').none { it == ".." } && '\\' !in path
                    ) {
                        "Invalid PowerPoint part path"
                    }
                    requireNotNull(rel["Id"]) to
                        Relationship(path, rel["Type"].orEmpty().substringAfterLast('/'))
                }
        }

        override fun close() = zip.close()
    }

    fun convert(source: File, output: File): Set<String> {
        val warnings = linkedSetOf<String>()
        Package(source).use { pkg ->
            val presentationPath =
                pkg.relationships("").values.firstOrNull { it.type == "officeDocument" }?.target
                    ?: "ppt/presentation.xml"
            val presentation = pkg.xml(presentationPath)
            val size = presentation.child("sldSz") ?: error("Missing PowerPoint slide dimensions")
            val width = (size.number("cx") / EMU).roundToInt()
            val height = (size.number("cy") / EMU).roundToInt()
            require(width in 1..14400 && height in 1..14400) {
                "Invalid PowerPoint slide dimensions"
            }
            val slides =
                presentation.child("sldIdLst")?.children.orEmpty().filter { it.name == "sldId" }
            require(slides.size in 1..500) { "PowerPoint must contain between 1 and 500 slides" }
            val rels = pkg.relationships(presentationPath)
            SlidePdfWriter(output, slides.size).use { pdf ->
                slides.forEach { id ->
                    val path =
                        rels[id["r:id"]]?.takeIf { it.type == "slide" }?.target
                            ?: error("Missing PowerPoint slide relationship")
                    val slide = pkg.xml(path)
                    val slideRels = pkg.relationships(path)
                    val layoutPath =
                        slideRels.values.firstOrNull { it.type == "slideLayout" }?.target
                    val layout = layoutPath?.let(pkg::xml)
                    val layoutRels = layoutPath?.let(pkg::relationships).orEmpty()
                    val masterPath =
                        layoutRels.values.firstOrNull { it.type == "slideMaster" }?.target
                    val master = masterPath?.let(pkg::xml)
                    val masterRels = masterPath?.let(pkg::relationships).orEmpty()
                    val theme =
                        masterRels.values.firstOrNull { it.type == "theme" }?.target?.let(pkg::xml)
                    val renderer = SlideRenderer(pkg, warnings, theme, master, layout, presentation)
                    val scale = 2048f / maxOf(width, height)
                    val bitmap =
                        Bitmap.createBitmap(
                            (width * scale).roundToInt().coerceAtLeast(1),
                            (height * scale).roundToInt().coerceAtLeast(1),
                            Bitmap.Config.ARGB_8888,
                        )
                    try {
                        val canvas = Canvas(bitmap)
                        canvas.scale(
                            bitmap.width.toFloat() / width,
                            bitmap.height.toFloat() / height,
                        )
                        canvas.drawColor(Color.WHITE)
                        val background =
                            listOfNotNull(slide, layout, master).firstNotNullOfOrNull {
                                it.child("cSld")?.child("bg")
                            }
                        renderer.background(canvas, background)
                        if (slide["showMasterSp"] != "0" && slide["showMasterSp"] != "false") {
                            if (
                                layout?.get("showMasterSp") != "0" &&
                                    layout?.get("showMasterSp") != "false"
                            )
                                renderer.layer(canvas, master, masterRels, true)
                            renderer.layer(canvas, layout, layoutRels, true)
                        }
                        renderer.layer(canvas, slide, slideRels, false)
                        if (slide.child("timing") != null || slide.child("transition") != null)
                            warnings.add("Animations and transitions are omitted")
                        pdf.page(bitmap, width, height)
                    } finally {
                        bitmap.recycle()
                    }
                }
                pdf.finish()
            }
        }
        return warnings
    }

    private class SlideRenderer(
        val pkg: Package,
        val warnings: MutableSet<String>,
        theme: Node?,
        val master: Node?,
        val layout: Node?,
        val presentation: Node,
    ) {
        private val colors =
            theme?.descendants("clrScheme")?.firstOrNull()?.children.orEmpty().associate {
                it.name to it.children.firstOrNull()
            }
        private val colorMap = master?.child("clrMap")?.attrs.orEmpty()

        private fun color(node: Node?, fallback: Int = Color.BLACK): Int {
            if (node == null) return fallback
            val raw =
                when (node.name) {
                    "srgbClr" -> node["val"]
                    "sysClr" -> node["lastClr"]
                    "schemeClr" -> {
                        val key = node["val"].orEmpty()
                        val resolved =
                            colorMap[key]
                                ?: when (key) {
                                    "bg1" -> "lt1"
                                    "tx1" -> "dk1"
                                    "bg2" -> "lt2"
                                    "tx2" -> "dk2"
                                    else -> key
                                }
                        return color(colors[resolved], fallback)
                    }
                    else ->
                        return color(
                            node.children.firstOrNull {
                                it.name in setOf("srgbClr", "sysClr", "schemeClr")
                            },
                            fallback,
                        )
                }
            var result =
                raw?.takeIf { it.matches(Regex("[0-9A-Fa-f]{6}")) }
                    ?.let { Color.parseColor("#$it") } ?: fallback
            node.child("alpha")?.let {
                result =
                    (result and 0x00ffffff) or
                        ((255f * it.number("val", 100000f) / 100000f)
                            .roundToInt()
                            .coerceIn(0, 255) shl 24)
            }
            if (node.children.any { it.name != "alpha" })
                warnings.add("Some theme effects are simplified")
            return result
        }

        fun background(canvas: Canvas, background: Node?) {
            val props =
                background?.child("bgPr")
                    ?: run {
                        if (background != null) warnings.add("Some backgrounds are simplified")
                        return
                    }
            props.child("solidFill")?.let { canvas.drawColor(color(it, Color.WHITE)) }
            if (props.children.any { it.name in setOf("blipFill", "gradFill", "pattFill") })
                warnings.add("Some backgrounds are omitted")
        }

        fun layer(canvas: Canvas, root: Node?, rels: Map<String, Relationship>, template: Boolean) {
            root?.child("cSld")?.child("spTree")?.children?.forEach {
                draw(canvas, it, rels, template)
            }
        }

        private fun placeholder(node: Node) = node.descendants("ph").firstOrNull()

        private fun inherited(node: Node, root: Node?): Node? {
            val ph = placeholder(node) ?: return null
            return root?.child("cSld")?.child("spTree")?.children?.firstOrNull {
                val candidate = placeholder(it)
                candidate != null &&
                    if (ph["idx"] != null) candidate["idx"] == ph["idx"]
                    else candidate["type"].orEmpty() == ph["type"].orEmpty()
            }
        }

        private fun draw(
            canvas: Canvas,
            node: Node,
            rels: Map<String, Relationship>,
            template: Boolean,
        ) {
            if (node.name in setOf("nvGrpSpPr", "grpSpPr", "extLst")) return
            if (template && placeholder(node) != null) return
            if (node.name !in setOf("sp", "pic", "cxnSp", "grpSp")) {
                warnings.add("Charts, tables, SmartArt and other unsupported objects are omitted")
                return
            }
            if (node.name == "grpSp") {
                val xfrm = node.child("grpSpPr")?.child("xfrm") ?: return
                val ext = xfrm.child("ext") ?: return
                val childExt = xfrm.child("chExt") ?: return
                val cx = childExt.number("cx")
                val cy = childExt.number("cy")
                require(cx > 0f && cy > 0f) { "Invalid PowerPoint group dimensions" }
                val off = xfrm.child("off")
                val childOff = xfrm.child("chOff")
                canvas.save()
                canvas.translate((off?.number("x") ?: 0f) / EMU, (off?.number("y") ?: 0f) / EMU)
                transform(canvas, xfrm, ext.number("cx") / EMU, ext.number("cy") / EMU)
                canvas.scale(ext.number("cx") / cx, ext.number("cy") / cy)
                canvas.translate(
                    -(childOff?.number("x") ?: 0f) / EMU,
                    -(childOff?.number("y") ?: 0f) / EMU,
                )
                node.children.forEach { draw(canvas, it, rels, template) }
                canvas.restore()
                return
            }
            val layoutShape = inherited(node, layout)
            val masterShape = inherited(layoutShape ?: node, master)
            val hierarchy = listOfNotNull(node, layoutShape, masterShape)
            val props = hierarchy.mapNotNull { it.child("spPr") }
            val xfrm = props.firstNotNullOfOrNull { it.child("xfrm") }
            if (xfrm == null) {
                warnings.add("Objects without supported positioning are omitted")
                return
            }
            val off = xfrm.child("off")
            val ext = xfrm.child("ext") ?: return
            val w = ext.number("cx") / EMU
            val h = ext.number("cy") / EMU
            require(w.isFinite() && h.isFinite() && w >= 0 && h >= 0 && w <= 14400 && h <= 14400) {
                "Invalid PowerPoint object dimensions"
            }
            canvas.save()
            try {
                canvas.translate((off?.number("x") ?: 0f) / EMU, (off?.number("y") ?: 0f) / EMU)
                transform(canvas, xfrm, w, h)
                if (node.name == "pic") image(canvas, node, rels, w, h)
                else {
                    shape(canvas, props, node, w, h)
                    text(canvas, node, hierarchy, w, h)
                }
                if (
                    node.descendants("effectLst").any { it.children.isNotEmpty() } ||
                        node.descendants("scene3d").isNotEmpty()
                )
                    warnings.add("Some visual effects are omitted")
            } finally {
                canvas.restore()
            }
        }

        private fun transform(canvas: Canvas, xfrm: Node, w: Float, h: Float) {
            canvas.rotate(xfrm.number("rot") / 60000f, w / 2, h / 2)
            canvas.scale(
                if (xfrm["flipH"] in setOf("1", "true")) -1f else 1f,
                if (xfrm["flipV"] in setOf("1", "true")) -1f else 1f,
                w / 2,
                h / 2,
            )
        }

        private fun image(
            canvas: Canvas,
            node: Node,
            rels: Map<String, Relationship>,
            w: Float,
            h: Float,
        ) {
            val fill = node.child("blipFill") ?: return
            val rel = fill.child("blip")?.get("r:embed")?.let(rels::get)
            if (rel == null || rel.type != "image") {
                warnings.add("Linked images are omitted")
                return
            }
            val bytes = pkg.bytes(rel.target, 32L * 1024 * 1024)
            val bitmap =
                try {
                    DocumentImport.decodeImage(
                        ImageDecoder.createSource(ByteBuffer.wrap(bytes)),
                        2048,
                    )
                } catch (_: java.io.IOException) {
                    warnings.add("Unsupported embedded images are omitted")
                    return
                }
            try {
                val crop = fill.child("srcRect")
                fun edge(name: String) = (crop?.number(name) ?: 0f) / 100000f
                val src =
                    Rect(
                        (bitmap.width * edge("l")).roundToInt().coerceIn(0, bitmap.width),
                        (bitmap.height * edge("t")).roundToInt().coerceIn(0, bitmap.height),
                        (bitmap.width * (1 - edge("r"))).roundToInt().coerceIn(0, bitmap.width),
                        (bitmap.height * (1 - edge("b"))).roundToInt().coerceIn(0, bitmap.height),
                    )
                if (!src.isEmpty)
                    canvas.drawBitmap(
                        bitmap,
                        src,
                        RectF(0f, 0f, w, h),
                        Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
                    )
            } finally {
                bitmap.recycle()
            }
        }

        private fun shape(canvas: Canvas, props: List<Node>, node: Node, w: Float, h: Float) {
            val geometry =
                props.firstNotNullOfOrNull { it.child("prstGeom") }?.get("prst")
                    ?: if (node.name == "cxnSp") "line" else "rect"
            if (
                geometry !in setOf("rect", "roundRect", "ellipse", "line") ||
                    props.any { it.child("custGeom") != null }
            ) {
                warnings.add("Unsupported shapes are simplified to rectangles")
            }
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            fun draw() {
                when (geometry) {
                    "ellipse" -> canvas.drawOval(0f, 0f, w, h, paint)
                    "roundRect" ->
                        canvas.drawRoundRect(0f, 0f, w, h, minOf(w, h) / 6, minOf(w, h) / 6, paint)
                    "line" -> canvas.drawLine(0f, 0f, w, h, paint)
                    else -> canvas.drawRect(0f, 0f, w, h, paint)
                }
            }
            val fill =
                props.firstNotNullOfOrNull { p ->
                    p.children.firstOrNull {
                        it.name in setOf("noFill", "solidFill", "gradFill", "blipFill", "pattFill")
                    }
                }
            val style = node.child("style")
            if (
                fill?.name == "solidFill" ||
                    (fill == null &&
                        style?.child("fillRef")?.get("idx") != "0" &&
                        style?.child("fillRef") != null)
            ) {
                paint.color = color(fill ?: style?.child("fillRef"))
                if (geometry != "line") draw()
            } else if (fill != null && fill.name != "noFill")
                warnings.add("Some shape fills are omitted")
            val line = props.firstNotNullOfOrNull { it.child("ln") }
            if (
                line?.child("noFill") == null &&
                    (line != null ||
                        geometry == "line" ||
                        style?.child("lnRef")?.get("idx")?.let { it != "0" } == true)
            ) {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = (line?.number("w", EMU) ?: EMU) / EMU
                paint.color = color(line?.child("solidFill") ?: style?.child("lnRef"))
                draw()
                if (
                    line?.children?.any { it.name in setOf("headEnd", "tailEnd", "prstDash") } ==
                        true
                )
                    warnings.add("Some line styles are simplified")
            }
        }

        private fun text(canvas: Canvas, node: Node, hierarchy: List<Node>, w: Float, h: Float) {
            val body = node.child("txBody") ?: return
            if (w < 1 || h < 1) return
            val bodyProps = hierarchy.firstNotNullOfOrNull { it.child("txBody")?.child("bodyPr") }
            if (bodyProps?.get("vert")?.let { it != "horz" } == true)
                warnings.add("Vertical text is rendered horizontally")
            val left = (bodyProps?.number("lIns", 91440f) ?: 91440f) / EMU
            val right = (bodyProps?.number("rIns", 91440f) ?: 91440f) / EMU
            val top = (bodyProps?.number("tIns", 45720f) ?: 45720f) / EMU
            val bottom = (bodyProps?.number("bIns", 45720f) ?: 45720f) / EMU
            val available = (w - left - right).roundToInt()
            if (available < 1) return
            val phType = placeholder(node)?.get("type")
            val textStyle =
                master
                    ?.child("txStyles")
                    ?.child(
                        when (phType) {
                            "title",
                            "ctrTitle" -> "titleStyle"
                            "body",
                            "subTitle",
                            "obj" -> "bodyStyle"
                            else -> "otherStyle"
                        }
                    )
            val layouts =
                body.children
                    .filter { it.name == "p" }
                    .map { paragraph ->
                        val pPr = paragraph.child("pPr")
                        val level = ((pPr?.number("lvl") ?: 0f).toInt().coerceIn(0, 8) + 1)
                        val defaults =
                            listOfNotNull(pPr) +
                                hierarchy.drop(1).mapNotNull {
                                    it.child("txBody")?.child("p")?.child("pPr")
                                } +
                                hierarchy.mapNotNull {
                                    it.child("txBody")?.child("lstStyle")?.child("lvl${level}pPr")
                                } +
                                listOfNotNull(
                                    textStyle?.child("lvl${level}pPr"),
                                    presentation.child("defaultTextStyle")?.child("lvl${level}pPr"),
                                )
                        val defaultRun = defaults.mapNotNull { it.child("defRPr") }
                        val baseSize =
                            (defaultRun.firstNotNullOfOrNull { it["sz"]?.toFloatOrNull() }
                                ?: 1800f) / 100f
                        val paint =
                            TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                                textSize = baseSize.coerceIn(1f, 400f)
                                color = Color.BLACK
                            }
                        val styled = SpannableStringBuilder()
                        val bullet =
                            defaults.firstNotNullOfOrNull {
                                it.children.firstOrNull { n ->
                                    n.name in setOf("buChar", "buNone", "buAutoNum")
                                }
                            }
                        if (bullet?.name == "buChar")
                            styled.append(bullet["char"] ?: "•").append(" ")
                        if (bullet?.name == "buAutoNum") {
                            styled.append("• ")
                            warnings.add("Numbered lists use bullets")
                        }
                        paragraph.children.forEach { run ->
                            if (run.name == "br") styled.append('\n')
                            if (run.name in setOf("r", "fld")) {
                                val start = styled.length
                                styled.append(run.child("t")?.text.orEmpty())
                                val style = listOfNotNull(run.child("rPr")) + defaultRun
                                fun attr(key: String) = style.firstNotNullOfOrNull { it[key] }
                                fun span(value: Any) {
                                    if (styled.length > start)
                                        styled.setSpan(
                                            value,
                                            start,
                                            styled.length,
                                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                                        )
                                }
                                val size =
                                    attr("sz")?.toFloatOrNull()?.div(100f)?.coerceIn(1f, 400f)
                                        ?: paint.textSize
                                span(RelativeSizeSpan(size / paint.textSize))
                                span(
                                    ForegroundColorSpan(
                                        color(
                                            style.firstNotNullOfOrNull { it.child("solidFill") }
                                                ?: node.child("style")?.child("fontRef")
                                        )
                                    )
                                )
                                val bold = attr("b") in setOf("1", "true")
                                val italic = attr("i") in setOf("1", "true")
                                span(
                                    StyleSpan(
                                        (if (bold) Typeface.BOLD else 0) or
                                            (if (italic) Typeface.ITALIC else 0)
                                    )
                                )
                                if (attr("u")?.let { it != "none" } == true) span(UnderlineSpan())
                            }
                        }
                        val alignment =
                            when (defaults.firstNotNullOfOrNull { it["algn"] }) {
                                "ctr" -> Layout.Alignment.ALIGN_CENTER
                                "r" -> Layout.Alignment.ALIGN_OPPOSITE
                                else -> Layout.Alignment.ALIGN_NORMAL
                            }
                        StaticLayout.Builder.obtain(styled, 0, styled.length, paint, available)
                            .setAlignment(alignment)
                            .setIncludePad(false)
                            .build()
                    }
            val totalHeight = layouts.sumOf { it.height }.toFloat()
            val availableHeight = h - top - bottom
            if (totalHeight > availableHeight)
                warnings.add("Some text overflows its box and is clipped")
            val offset =
                when (bodyProps?.get("anchor")) {
                    "ctr" -> (availableHeight - totalHeight) / 2
                    "b" -> availableHeight - totalHeight
                    else -> 0f
                }.coerceAtLeast(0f)
            canvas.save()
            canvas.clipRect(0f, 0f, w, h)
            canvas.translate(left, top + offset)
            layouts.forEach {
                it.draw(canvas)
                canvas.translate(0f, it.height.toFloat())
            }
            canvas.restore()
        }
    }
}

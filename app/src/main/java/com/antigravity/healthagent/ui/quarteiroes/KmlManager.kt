package com.antigravity.healthagent.ui.quarteiroes

import android.graphics.Color
import com.google.android.gms.maps.model.LatLng
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.InputStream
import java.util.UUID
import javax.inject.Inject
import javax.xml.parsers.DocumentBuilderFactory

// --- Data Models ---

data class KmlFolder(
    val id: String,
    val name: String,
    val isVisible: Boolean,
    val children: List<KmlFolder>,
    val placemarks: List<KmlPlacemark>
) {
    fun getBounds(): com.google.android.gms.maps.model.LatLngBounds? {
        val builder = com.google.android.gms.maps.model.LatLngBounds.builder()
        var hasPoints = false
        
        fun processFolder(f: KmlFolder) {
            f.placemarks.forEach { p ->
                p.geometry.extractCoordinates().forEach { 
                    builder.include(it)
                    hasPoints = true
                }
            }
            f.children.forEach { processFolder(it) }
        }
        
        processFolder(this)
        return if (hasPoints) builder.build() else null
    }
}

data class KmlPlacemark(
    val name: String,
    val description: String?,
    val styleId: String?,
    val geometry: KmlGeometry,
    val style: KmlStyle? = null // Resolved style
)

sealed class KmlGeometry {
    abstract fun extractCoordinates(): List<LatLng>
    
    data class Point(val coordinate: LatLng) : KmlGeometry() {
        override fun extractCoordinates(): List<LatLng> = listOf(coordinate)
    }
    
    data class LineString(val coordinates: List<LatLng>) : KmlGeometry() {
        override fun extractCoordinates(): List<LatLng> = coordinates
    }
    
    data class Polygon(val outerBoundary: List<LatLng>, val innerBoundaries: List<List<LatLng>> = emptyList()) : KmlGeometry() {
        override fun extractCoordinates(): List<LatLng> = outerBoundary + innerBoundaries.flatten()
    }
    
    data class MultiGeometry(val geometries: List<KmlGeometry>) : KmlGeometry() {
        override fun extractCoordinates(): List<LatLng> = geometries.flatMap { it.extractCoordinates() }
    }
}

data class KmlStyle(
    val lineStyle: KmlLineStyle? = null,
    val polyStyle: KmlPolyStyle? = null,
    val iconStyle: KmlIconStyle? = null
)

data class KmlIconStyle(
    val scale: Float = 1f,
    val heading: Float = 0f,
    val href: String? = null,
    val color: Int = Color.WHITE
)

data class KmlLineStyle(
    val color: Int = Color.BLACK, // Android Color Int
    val width: Float = 1f
)

data class KmlPolyStyle(
    val color: Int = Color.BLACK, // Android Color Int
    val fill: Boolean = true,
    val outline: Boolean = true
)

class KmlManager @Inject constructor() {

    private val styleMap = mutableMapOf<String, KmlStyle>()
    private val styleMapMap = mutableMapOf<String, String>() // Map styleMap ID -> Normal Style ID (simplified)

    fun parseKml(inputStream: InputStream): Result<List<KmlFolder>> {
        styleMap.clear()
        styleMapMap.clear()
        
        return try {
            val factory = DocumentBuilderFactory.newInstance()
            // NamespaceAware=true to properly handle Google Earth XML namespaces
            factory.isNamespaceAware = true
            val builder = factory.newDocumentBuilder()
            val document = builder.parse(inputStream)
            
            var rootNode: Node? = document.documentElement
            if (rootNode == null) return Result.failure(Exception("KML sem elemento raiz"))

            if (rootNode.localName == "kml" || rootNode.nodeName == "kml" || rootNode.nodeName.endsWith(":kml")) {
                val kids = rootNode.childNodes
                for(i in 0 until kids.length) {
                    val k = kids.item(i)
                    if (k.nodeType == Node.ELEMENT_NODE && (k.localName == "Document" || k.localName == "Folder" || k.nodeName == "Document" || k.nodeName == "Folder")) {
                        rootNode = k
                        break
                    }
                }
            }
            
            if (rootNode?.nodeType != Node.ELEMENT_NODE) {
                return Result.failure(Exception("Elemento KML Document ou Folder não encontrado"))
            }
            
            // 1. Parse Global Styles & StyleMaps
            parseGlobalStyles(rootNode as Element)

            // 2. Parse Folder Structure
            Result.success(parseChildren(rootNode))
        } catch (e: Exception) {
            android.util.Log.e("KmlManager", "Error parsing KML", e)
            Result.failure(e)
        }
    }

    private fun parseGlobalStyles(document: Element) {
        val children = document.childNodes
        android.util.Log.d("KmlManager", "Scanning Document children for Styles. Total children: ${children.length}")
        
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val name = element.localName ?: element.nodeName
            
            if (name == "Style" || name == "CascadingStyle") {
                 val id = getId(element)
                 if (id.isNotEmpty()) {
                      val style = parseStyleNode(element)
                      styleMap["#$id"] = style
                      styleMap[id] = style
                     android.util.Log.d("KmlManager", "Parsed Global Style: $name id=$id")
                 } else {
                     android.util.Log.w("KmlManager", "Skipping Global Style ($name) without ID")
                 }
            } else if (name == "StyleMap") {
                 val id = getId(element)
                 if (id.isNotEmpty()) {
                     parseStyleMap(element, id)
                 }
            }
        }
    }

    private fun getId(element: Element): String {
        var id = element.getAttribute("id")
        if (id.isEmpty()) id = element.getAttribute("xml:id")
        if (id.isEmpty()) id = element.getAttribute("kml:id")
        // Also check getAttributeNS if needed, but simple names often tricky
        return id
    }

    private fun parseStyleMap(node: Element, id: String) {
         val pairs = node.getElementsByTagNameNS("*", "Pair")
         for (j in 0 until pairs.length) {
             val pair = pairs.item(j) as Element
             val key = getChildValue(pair, "key")
             
             if (key == "normal") {
                 // CASE A: Reference to external Style via styleUrl
                 val styleUrl = getChildValue(pair, "styleUrl")
                 if (styleUrl != null) {
                     styleMapMap["#$id"] = styleUrl
                     styleMapMap[id] = styleUrl
                     android.util.Log.d("KmlManager", "Mapped StyleMap (ref): $id -> $styleUrl")
                     return // Found normal style, done
                 }

                 // CASE B: Inline Style definition
                 val inlineStyles = pair.getElementsByTagNameNS("*", "Style")
                 if (inlineStyles.length > 0) {
                     val styleNode = inlineStyles.item(0) as Element
                     val style = parseStyleNode(styleNode)
                     
                     styleMap["#$id"] = style
                     styleMap[id] = style
                     android.util.Log.d("KmlManager", "Mapped StyleMap (inline): $id")
                     return
                 }
             }
         }
    }

    private fun parseStyleNode(node: Element): KmlStyle {
        // Handle CascadingStyle wrapping a Style
        var styleSource = node
        val name = node.localName ?: node.nodeName
        if (name == "CascadingStyle") {
             val subStyles = node.getElementsByTagNameNS("*", "Style")
             // If nested Style exists, use it. Otherwise, assume CascadingStyle acts as Style container (unlikely but safe)
             if (subStyles.length > 0) {
                 styleSource = subStyles.item(0) as Element
             }
        }

        var lineStyle: KmlLineStyle? = null
        var polyStyle: KmlPolyStyle? = null
        var iconStyle: KmlIconStyle? = null

        val lineStyleNode = getChildNode(styleSource, "LineStyle")
        if (lineStyleNode != null) {
            val colorStr = getChildValue(lineStyleNode as Element, "color") ?: "ff000000"
            val widthStr = getChildValue(lineStyleNode, "width") ?: "1.0"
            lineStyle = KmlLineStyle(parseKmlColor(colorStr), widthStr.toFloatOrNull() ?: 1f)
        }

        val polyStyleNode = getChildNode(styleSource, "PolyStyle")
        if (polyStyleNode != null) {
            val colorStr = getChildValue(polyStyleNode as Element, "color") ?: "ff000000"
            val fillStr = getChildValue(polyStyleNode, "fill") ?: "1"
            val outlineStr = getChildValue(polyStyleNode, "outline") ?: "1"
            polyStyle = KmlPolyStyle(
                parseKmlColor(colorStr),
                fillStr == "1" || fillStr.equals("true", true),
                outlineStr == "1" || outlineStr.equals("true", true)
            )
        }

        val iconStyleNode = getChildNode(styleSource, "IconStyle")
        if (iconStyleNode != null) {
            val element = iconStyleNode as Element
            val colorStr = getChildValue(element, "color") ?: "ffffffff"
            val scaleStr = getChildValue(element, "scale") ?: "1.0"
            val headingStr = getChildValue(element, "heading") ?: "0.0"
            
            var href: String? = null
            val iconNode = getChildNode(element, "Icon")
            if (iconNode != null) {
                href = getChildValue(iconNode as Element, "href")?.trim()
            }

            iconStyle = KmlIconStyle(
                scale = scaleStr.toFloatOrNull() ?: 1f,
                heading = headingStr.toFloatOrNull() ?: 0f,
                href = href,
                color = parseKmlColor(colorStr)
            )
        }

        return KmlStyle(lineStyle, polyStyle, iconStyle)
    }

    private fun parseChildren(parentElement: Element): List<KmlFolder> {
        val folders = mutableListOf<KmlFolder>()
        val childNodes = parentElement.childNodes
        
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            
            val element = node as Element
            val nodeName = element.localName ?: element.nodeName

            if (nodeName == "Folder" || nodeName == "Document") {
                val name = getChildValue(element, "name") ?: "Unnamed Folder"
                
                 var isVisible = true
                val visibilityNode = getChildNode(element, "visibility")
                if (visibilityNode != null) {
                    isVisible = visibilityNode.textContent != "0"
                }
                
                val id = UUID.randomUUID().toString()
                
                // Recurse for children folders
                val childrenFolders = parseChildren(element)
                
                // Parse Placemarks in this folder
                val placemarks = parsePlacemarks(element)

                // Only add if it has content (or is a valid container)
                folders.add(KmlFolder(id, name, isVisible, childrenFolders, placemarks))
            }
        }
        return folders
    }

    private fun parsePlacemarks(folderElement: Element): List<KmlPlacemark> {
        val placemarks = mutableListOf<KmlPlacemark>()
        val childNodes = folderElement.childNodes
        
        for (i in 0 until childNodes.length) {
            val node = childNodes.item(i)
            if (node.nodeType != Node.ELEMENT_NODE) continue
            val element = node as Element
            val nodeName = element.localName ?: element.nodeName

            if (nodeName == "Placemark") {
                val name = getChildValue(element, "name") ?: "Unnamed Placemark"
                val description = getChildValue(element, "description")
                val styleUrl = getChildValue(element, "styleUrl")
                
                val geometry = parseGeometry(element)
                
                if (geometry != null) {
                    var resolvedStyle = resolveStyle(styleUrl)
                    
                    // Fallback: Check for Inline Style
                    if (resolvedStyle == null) {
                        val inlineStyleNode = getChildNode(element, "Style")
                        if (inlineStyleNode != null) {
                            resolvedStyle = parseStyleNode(inlineStyleNode as Element)
                        }
                    }

                    if (resolvedStyle == null && styleUrl != null) {
                        // android.util.Log.w("KmlManager", "Failed to resolve style for Placemark '$name' with styleUrl='$styleUrl'")
                    }
                    placemarks.add(KmlPlacemark(name, description, styleUrl, geometry, resolvedStyle))
                }
            }
        }
        return placemarks
    }

    private fun resolveStyle(styleUrl: String?): KmlStyle? {
        if (styleUrl == null) return null
        // 1. Direct lookup
        var style = styleMap[styleUrl]
        if (style != null) return style

        // 2. StyleMap lookup
        val linkedStyleUrl = styleMapMap[styleUrl]
        if (linkedStyleUrl != null) {
            return styleMap[linkedStyleUrl]
        }
        
        return null
    }

    private fun parseGeometry(placemarkElement: Element): KmlGeometry? {
        // Check for specific geometry types
        var node = getChildNode(placemarkElement, "LineString")
        if (node != null) return parseLineString(node as Element)

        node = getChildNode(placemarkElement, "Polygon")
        if (node != null) return parsePolygon(node as Element)

        node = getChildNode(placemarkElement, "Point")
        if (node != null) return parsePoint(node as Element)
        
        node = getChildNode(placemarkElement, "MultiGeometry")
        if (node != null) {
             val geometries = mutableListOf<KmlGeometry>()
             val children = node.childNodes
             for(i in 0 until children.length) {
                 val child = children.item(i)
                 if(child.nodeType == Node.ELEMENT_NODE) {
                      // Hacky recursion/wrapping for MultiGeometry components
                      // We can just construct a facade element or parse directly
                      val geom = parseGeometryComponent(child as Element)
                      if(geom != null) geometries.add(geom)
                 }
             }
             return KmlGeometry.MultiGeometry(geometries)
        }

        return null
    }

    private fun parseGeometryComponent(element: Element): KmlGeometry? {
        val name = element.localName ?: element.nodeName
        return when(name) {
            "LineString" -> parseLineString(element)
            "Polygon" -> parsePolygon(element)
            "Point" -> parsePoint(element)
            else -> null
        }
    }

    private fun parseLineString(element: Element): KmlGeometry.LineString {
        val coordinatesStr = getChildValue(element, "coordinates") ?: ""
        return KmlGeometry.LineString(parseCoordinates(coordinatesStr))
    }

    private fun parsePolygon(element: Element): KmlGeometry.Polygon {
        val outerBoundaryNode = getChildNode(element, "outerBoundaryIs")
        val outerCoords = if (outerBoundaryNode != null) {
            val linearRing = getChildNode(outerBoundaryNode as Element, "LinearRing")
            val coordsStr = if (linearRing != null) getChildValue(linearRing as Element, "coordinates") else null
            parseCoordinates(coordsStr ?: "")
        } else {
            emptyList()
        }
        return KmlGeometry.Polygon(outerCoords) // Inner boundaries skipped for simplicity
    }

    private fun parsePoint(element: Element): KmlGeometry.Point {
        val coordinatesStr = getChildValue(element, "coordinates") ?: ""
        val coords = parseCoordinates(coordinatesStr)
        return KmlGeometry.Point(coords.firstOrNull() ?: LatLng(0.0, 0.0))
    }

    private fun parseCoordinates(text: String): List<LatLng> {
        val list = mutableListOf<LatLng>()
        val items = text.trim().split("\\s+".toRegex())
        for (item in items) {
            val parts = item.split(",")
            if (parts.size >= 2) {
                try {
                    val lon = parts[0].toDouble()
                    val lat = parts[1].toDouble()
                    list.add(LatLng(lat, lon))
                } catch (e: NumberFormatException) {
                    // Ignore malformed
                }
            }
        }
        return list
    }

    private fun getChildValue(parent: Element, tagName: String): String? {
        return getChildNode(parent, tagName)?.textContent
    }

    private fun getChildNode(parent: Element, tagName: String): Node? {
        val children = parent.childNodes
        for (i in 0 until children.length) {
            val node = children.item(i)
            if (node.nodeType == Node.ELEMENT_NODE) {
                val nodeName = node.localName ?: node.nodeName
                if (nodeName == tagName) {
                    return node
                }
            }
        }
        return null
    }

    private fun parseKmlColor(kmlColor: String): Int {
        // KML Color: aabbggrr (hex)
        // Android Color: aarrggbb (int)
        // We need to swap Red and Blue.
        try {
            if (kmlColor.length == 8) {
                val a = kmlColor.substring(0, 2)
                val b = kmlColor.substring(2, 4)
                val g = kmlColor.substring(4, 6)
                val r = kmlColor.substring(6, 8)
                val androidColorHex = "#$a$r$g$b"
                return Color.parseColor(androidColorHex)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return Color.BLACK
    }
}

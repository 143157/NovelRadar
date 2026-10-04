package com.novelradar.app

import com.google.common.truth.Truth.assertThat
import java.io.File
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Test
import org.w3c.dom.Element

/** Offline resource contract; launcher rendering and device masks remain platform behavior. */
class LauncherIconContractTest {
    @Test fun manifestAndMasksUseSelectedArtwork() {
        val manifest = xmlRoot("src/main/AndroidManifest.xml")
        val applications = manifest.getElementsByTagName("application")
        assertThat(applications.length).isEqualTo(1)
        val application = applications.item(0) as Element
        assertThat(application.androidAttribute("icon")).isEqualTo("@mipmap/ic_launcher")
        assertThat(application.androidAttribute("roundIcon")).isEqualTo("@mipmap/ic_launcher_round")

        for (name in listOf("ic_launcher", "ic_launcher_round")) {
            val legacy = xmlRoot("src/main/res/mipmap-anydpi/$name.xml")
            assertThat(legacy.localName).isEqualTo("bitmap")
            assertThat(legacy.androidAttribute("src")).isEqualTo("@drawable/user_app_icon")
            assertThat(legacy.androidAttribute("gravity")).isEqualTo("fill")

            val adaptive = xmlRoot("src/main/res/mipmap-anydpi-v26/$name.xml")
            assertThat(adaptive.localName).isEqualTo("adaptive-icon")
            val layers = adaptive.elementChildren()
            assertThat(layers.map { it.localName }).containsExactly("background", "foreground").inOrder()
            assertThat(layers[0].androidAttribute("drawable")).isEqualTo("@color/launcher_background")
            assertThat(layers[1].androidAttribute("drawable")).isEqualTo("@drawable/launcher_icon_foreground")
        }

        val foreground = xmlRoot("src/main/res/drawable/launcher_icon_foreground.xml")
        assertThat(foreground.localName).isEqualTo("inset")
        assertThat(foreground.androidAttribute("inset")).isEqualTo("18dp")
        val foregroundChildren = foreground.elementChildren()
        assertThat(foregroundChildren).hasSize(1)
        val bitmap = foregroundChildren.single()
        assertThat(bitmap.localName).isEqualTo("bitmap")
        assertThat(bitmap.androidAttribute("src")).isEqualTo("@drawable/user_app_icon")
        assertThat(bitmap.androidAttribute("gravity")).isEqualTo("fill")

        val colors = xmlRoot("src/main/res/values/launcher_icon_colors.xml")
        assertThat(colors.localName).isEqualTo("resources")
        val backgroundColors = colors.elementChildren().filter {
            it.localName == "color" && it.getAttribute("name") == "launcher_background"
        }
        assertThat(backgroundColors).hasSize(1)
        assertThat(backgroundColors.single().textContent.trim()).isEqualTo("#4E529E")
    }

    @Test fun pngPreservesUserSelectedBytes() {
        val artwork = File("src/main/res/drawable-nodpi/user_app_icon.png")
        assertThat(artwork.exists()).isTrue()
        assertThat(artwork.isFile).isTrue()
        val sha256 = MessageDigest.getInstance("SHA-256").digest(artwork.readBytes())
            .joinToString("") { "%02X".format(it.toInt() and 0xff) }
        assertThat(sha256).isEqualTo("EAE824602E571E34E47D5FB6B7290B26422E0061AB5E48B68DCDBA95785E1907")
    }

    private fun xmlRoot(path: String): Element {
        val file = File(path)
        assertThat(file.exists()).isTrue()
        return DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file).documentElement
    }

    private fun Element.androidAttribute(name: String): String = getAttributeNS(
        "http://schemas.android.com/apk/res/android", name,
    )

    private fun Element.elementChildren(): List<Element> = (0 until childNodes.length)
        .map { childNodes.item(it) }.filterIsInstance<Element>()
}

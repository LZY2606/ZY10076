package de.shyim.shopware6.test.marker

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.daemon.RelatedItemLineMarkerInfo
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.shyim.shopware6.index.TwigBlockHashIndex

/**
 * Proves the fallback priority/scope invariant used by every block consumer
 * (gutter marker, go-to-declaration, versioning inspections and quick fix):
 *
 * When the sw_extends target cannot be resolved in the project, the upstream candidates of a
 * block come from every other file at the same view path, the overriding file itself excluded,
 * ordered Shopware core first, then vendor packages, then custom/plugins (see
 * TwigUtil.getUpstreamBlocks). The check goes through the real FileBasedIndex storage
 * (the plugin does not use StubIndex) and the TwigBlockMarker line-marker consumer.
 */
class TwigUpstreamPriorityTest : BasePlatformTestCase() {
    private val viewPath = "storefront/page/content/index.html.twig"

    private val corePath = "ShopwarePlatform/src/Storefront/Resources/views/$viewPath"
    private val vendorPath = "vendor/acme/theme/Resources/views/$viewPath"
    private val themePath = "custom/plugins/TcinnTheme/src/Resources/views/$viewPath"
    private val pluginPath = "MyPlugin/Resources/views/$viewPath"

    override fun setUp() {
        super.setUp()

        myFixture.addFileToProject(
            corePath,
            """
            {% block base_content %}
                <div>core</div>
            {% endblock %}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            vendorPath,
            """
            {% block base_content %}
                <div>vendor theme</div>
            {% endblock %}
            """.trimIndent()
        )
        myFixture.addFileToProject(
            themePath,
            """
            {% block base_content %}
                <div>custom theme</div>
            {% endblock %}
            """.trimIndent()
        )
    }

    fun testFallbackUpstreamIsScopedByViewPathAndOrderedCoreVendorCustom() {
        // the target bundle cannot be resolved, so the sw_extends chain is empty and the
        // consumers have to fall back to the other files at the same view path
        val override = myFixture.addFileToProject(
            pluginPath,
            """
            {% sw_extends '@UnknownBundle/$viewPath' %}

            {% block base_content %}
                <div>my override</div>
            {% endblock %}
            """.trimIndent()
        )

        // index side: all four files at the view path are indexed under the block name
        val indexedPaths = FileBasedIndex.getInstance()
            .getValues(TwigBlockHashIndex.key, "base_content", GlobalSearchScope.allScope(project))
            .map { it.absolutePath }

        assertEquals(4, indexedPaths.size)
        assertTrue(indexedPaths.any { it.contains("ShopwarePlatform") })
        assertTrue(indexedPaths.any { it.contains("/vendor/acme/") })
        assertTrue(indexedPaths.any { it.contains("custom/plugins/TcinnTheme") })
        assertTrue(indexedPaths.any { it.contains("/MyPlugin/") })

        // consumer side: the gutter marker offers the same three fallback candidates, the
        // overriding file itself excluded, ordered core, vendor, custom/plugins
        myFixture.configureFromExistingVirtualFile(override.virtualFile)

        val markers = myFixture.findAllGutters()
            .filterIsInstance<LineMarkerInfo.LineMarkerGutterIconRenderer<*>>()
            .map { it.lineMarkerInfo }

        assertEquals(listOf("Overrides block"), markers.mapNotNull { it.lineMarkerTooltip })

        val targetPaths = (markers.single() as RelatedItemLineMarkerInfo<*>).createGotoRelatedItems()
            .mapNotNull { it.element?.containingFile?.virtualFile?.path }

        assertEquals(3, targetPaths.size)
        assertTrue(targetPaths[0].contains("ShopwarePlatform"))
        assertTrue(targetPaths[1].contains("/vendor/acme/"))
        assertTrue(targetPaths[2].contains("custom/plugins/TcinnTheme"))
    }
}

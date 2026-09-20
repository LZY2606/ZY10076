package de.shyim.shopware6.test.inspection.quickfix

import com.intellij.psi.search.GlobalSearchScope
import com.intellij.testFramework.fixtures.BasePlatformTestCase
import com.intellij.util.indexing.FileBasedIndex
import de.shyim.shopware6.index.TwigBlockHashIndex
import de.shyim.shopware6.inspection.twig.TwigBlockHashMissing
import de.shyim.shopware6.util.TwigUtil

class TwigVersioningCommentQuickFixTest : BasePlatformTestCase() {
    override fun getTestDataPath(): String {
        return "src/test/testData/inspection/TwigBlockHashMissingTest/"
    }

    fun testQuickFixRecordsUpstreamHashAndIgnoresCommentedSiblingOverride() {
        myFixture.copyDirectoryToProject("ShopwarePlatform", "ShopwarePlatform")
        myFixture.enableInspections(TwigBlockHashMissing())

        // the sibling plugin sorts earlier by path and carries a versioning comment, so without
        // the !hasVersioningComment filtering its override hash would be recorded as upstream
        myFixture.addFileToProject(
            "AaaSiblingPlugin/Resources/views/storefront/page/content/index.html.twig",
            """
            {# shopware-block: siblingsownhash@1.0.0 #}
            {% block base_content %}
                <div>commented sibling override</div>
            {% endblock %}
            """.trimIndent()
        )

        val file = myFixture.addFileToProject(
            "MyPlugin/Resources/views/storefront/page/content/index.html.twig",
            """
            {% sw_extends '@Storefront/storefront/page/content/index.html.twig' %}

            {% block base_content %}
                <div>my override</div>
            {% endblock %}
            """.trimIndent()
        )

        val coreHash = FileBasedIndex.getInstance().getValues(
            TwigBlockHashIndex.key,
            "base_content",
            GlobalSearchScope.allScope(project)
        ).first {
            TwigUtil.isShopwareCoreTemplate(it.absolutePath)
        }.hash

        myFixture.configureFromExistingVirtualFile(file.virtualFile)
        myFixture.editor.caretModel.moveToOffset(myFixture.file.text.indexOf("block base_content") + 6)

        // the missing-comment inspection is the index-backed producer of this quick fix
        assertTrue(myFixture.getAllQuickFixes().any { it.familyName == "Add missing versioning comment" })

        val intention = myFixture.findSingleIntention("Add missing versioning comment")
        myFixture.launchAction(intention)

        myFixture.checkResult(
            """
            {% sw_extends '@Storefront/storefront/page/content/index.html.twig' %}

            {# shopware-block: $coreHash #}
            {% block base_content %}
                <div>my override</div>
            {% endblock %}
            """.trimIndent()
        )
    }
}

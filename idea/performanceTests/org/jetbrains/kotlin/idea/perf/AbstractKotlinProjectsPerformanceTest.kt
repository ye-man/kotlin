/*
 * Copyright 2010-2019 JetBrains s.r.o. and Kotlin Programming Language contributors.
 * Use of this source code is governed by the Apache 2.0 license that can be found in the license/LICENSE.txt file.
 */

package org.jetbrains.kotlin.idea.perf

import com.intellij.codeInsight.completion.CompletionType
import com.intellij.codeInsight.daemon.impl.HighlightInfo
import com.intellij.codeInsight.lookup.LookupElement
import com.intellij.codeInspection.ex.InspectionProfileImpl
import com.intellij.idea.IdeaTestApplication
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.CommandProcessor
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.fileEditor.FileEditorManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.project.ex.ProjectManagerEx
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.ProjectJdkTable
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.ChangeListManagerImpl
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiFile
import com.intellij.testFramework.UsefulTestCase
import com.intellij.testFramework.fixtures.IdeaTestExecutionPolicy
import com.intellij.testFramework.fixtures.IdeaTestFixtureFactory
import com.intellij.testFramework.fixtures.TempDirTestFixture
import com.intellij.testFramework.fixtures.impl.CodeInsightTestFixtureImpl
import com.intellij.testFramework.fixtures.impl.LightTempDirTestFixtureImpl
import org.jetbrains.kotlin.idea.KotlinFileType
import org.jetbrains.kotlin.idea.core.util.toPsiFile
import org.jetbrains.kotlin.idea.framework.KotlinSdkType
import org.jetbrains.kotlin.idea.inspections.UnusedSymbolInspection
import org.jetbrains.kotlin.idea.test.KotlinWithJdkAndRuntimeLightProjectDescriptor
import java.io.File

abstract class AbstractKotlinProjectsPerformanceTest : UsefulTestCase() {

    lateinit var myProject: Project

    private val stats: Stats = Stats("-perf")

    override fun setUp() {
        super.setUp()

        IdeaTestApplication.getInstance()
        ApplicationManager.getApplication().runWriteAction {
            val jdkTableImpl = JavaAwareProjectJdkTableImpl.getInstanceEx()
            val homePath = if (jdkTableImpl.internalJdk.homeDirectory!!.name == "jre") {
                jdkTableImpl.internalJdk.homeDirectory!!.parent.path
            } else {
                jdkTableImpl.internalJdk.homePath!!
            }

            val javaSdk = JavaSdk.getInstance()
            val j8 = javaSdk.createJdk("1.8", homePath)
            val internal = javaSdk.createJdk("IDEA jdk", homePath)

            val jdkTable = ProjectJdkTable.getInstance()
            jdkTable.addJdk(j8, testRootDisposable)
            jdkTable.addJdk(internal, testRootDisposable)
            KotlinSdkType.setUpIfNeeded()
        }
        UnusedSymbolInspection()
        InspectionProfileImpl.INIT_INSPECTIONS = true

        // warm up: open simple small project
        val project = innerPerfOpenProject("helloKotlin", "warm-up ")
        perfHighlightFile(project, "src/HelloMain.kt", "warm-up ")

        ProjectManagerEx.getInstanceEx().forceCloseProject(project, true)
    }

    protected fun getTempDirFixture(): TempDirTestFixture {
        val policy = IdeaTestExecutionPolicy.current()
        return if (policy != null)
            policy.createTempDirTestFixture()
        else
            LightTempDirTestFixtureImpl(true)
    }

    protected fun perfChangeDocument(fileName: String, note: String = "", block: (document: Document) -> Unit) =
        perfChangeDocument(myProject, fileName, note, block)

    private fun perfChangeDocument(
        project: Project,
        fileName: String,
        nameOfChange: String,
        block: (document: Document) -> Unit
    ) {
        val document = openEditor(project, fileName)
        val manager = PsiDocumentManager.getInstance(project)
        CommandProcessor.getInstance().executeCommand(project, {
            ApplicationManager.getApplication().runWriteAction {
                tcSimplePerfTest(fileName, "Changing doc $nameOfChange", stats) {
                    block(document)

                    manager.commitDocument(document)
                }
            }
        }, "change doc $fileName $nameOfChange", "")
    }

    protected fun perfOpenProject(name: String, path: String = "idea/testData/perfTest") {
        myProject = innerPerfOpenProject(name, path = path, note = "")
    }

    private fun innerPerfOpenProject(
        name: String,
        note: String,
        path: String = "idea/testData/perfTest"
    ): Project {
        var project: Project? = null

        val projectPath = "$path/$name"
        tcSimplePerfTest("", "Project ${note}opening $name", stats) {
            project = ProjectManager.getInstance().loadAndOpenProject(projectPath)
            ProjectManagerEx.getInstanceEx().openTestProject(project!!)

            // open + indexing + lots of other things
            //project = ProjectUtil.openOrImport(projectPath, null, false)

            disposeOnTearDown(Disposable { ProjectManagerEx.getInstanceEx().forceCloseProject(project!!, true) })
        }

        val changeListManagerImpl = ChangeListManager.getInstance(project!!) as ChangeListManagerImpl
        changeListManagerImpl.waitUntilRefreshed()

        return project!!
    }

    protected fun perfHighlightFile(name: String): List<HighlightInfo> =
        perfHighlightFile(myProject, name)


    private fun perfHighlightFile(
        project: Project,
        name: String,
        note: String = ""
    ): List<HighlightInfo> {
        val file = openFileInEditor(project, name)

        var highlightFile: List<HighlightInfo> = emptyList()
        tcSimplePerfTest(file.name, "Highlighting file $note${file.name}", stats) {
            highlightFile = highlightFile(file)
        }
        return highlightFile
    }

    inline fun attempts(block: (v: Int) -> Unit) {
        attempts(3, block)
    }

    inline fun attempts(count: Int, block: (v: Int) -> Unit) {
        for (attempt in 0..count) {
            block(attempt)
        }
    }

    fun perfAutoCompletion(
        name: String,
        before: String,
        suggestions: Array<String>,
        type: String,
        after: String
    ) {
        val factory = IdeaTestFixtureFactory.getFixtureFactory()
        val fixtureBuilder = factory.createLightFixtureBuilder(KotlinWithJdkAndRuntimeLightProjectDescriptor.INSTANCE)
        val tempDirFixture = getTempDirFixture()
        val fixture = factory.createCodeInsightFixture(fixtureBuilder.fixture, tempDirFixture)

        with(fixture) {
            setUp()
            configureByText(KotlinFileType.INSTANCE, before)
        }

        var complete: Array<LookupElement>? = null
        tcSimplePerfTest("", "Auto completion $name", stats) {
            with(fixture) {
                complete = complete(CompletionType.BASIC)
            }
        }

        val actualSuggestions = complete?.map { it.lookupString }?.toList() ?: emptyList()
        assertTrue(actualSuggestions.containsAll(suggestions.toList()))

        with(fixture) {
            type(type)
            checkResult(after)
            tearDown()
        }
    }

    private fun highlightFile(psiFile: PsiFile): List<HighlightInfo> {
        val document = FileDocumentManager.getInstance().getDocument(psiFile.virtualFile)!!
        val editor = EditorFactory.getInstance().getEditors(document)[0]
        return CodeInsightTestFixtureImpl.instantiateAndRun(psiFile, editor, IntArray(0), false)
    }

    private fun openFileInEditor(project: Project, name: String): PsiFile {
        val psiFile = projectFileByName(project, name)
        val vFile = psiFile.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(vFile, true)
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!
        assertNotNull(EditorFactory.getInstance().getEditors(document))
        disposeOnTearDown(Disposable { fileEditorManager.closeFile(vFile) })
        return psiFile
    }

    private fun openEditor(project: Project, name: String): Document {
        val psiFile = projectFileByName(project, name)
        val vFile = psiFile.virtualFile
        val fileEditorManager = FileEditorManager.getInstance(project)
        fileEditorManager.openFile(vFile, true)
        val document = FileDocumentManager.getInstance().getDocument(vFile)!!
        assertNotNull(EditorFactory.getInstance().getEditors(document))
        disposeOnTearDown(Disposable { fileEditorManager.closeFile(vFile) })
        return document
    }

    private fun projectFileByName(project: Project, name: String): PsiFile {
        val fileManager = VirtualFileManager.getInstance()
        val url = "file://${File("${project.basePath}/$name").absolutePath}"
        val virtualFile = fileManager.refreshAndFindFileByUrl(url)
        return virtualFile!!.toPsiFile(project)!!
    }
}
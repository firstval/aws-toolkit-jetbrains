// Copyright 2019 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.services.lambda.execution.local

import com.intellij.compiler.CompilerTestUtil
import com.intellij.execution.executors.DefaultDebugExecutor
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.projectRoots.impl.JavaAwareProjectJdkTableImpl
import com.intellij.openapi.roots.CompilerProjectExtension
import com.intellij.openapi.roots.ModuleRootModificationUtil
import com.intellij.psi.PsiJavaFile
import com.intellij.testFramework.PlatformTestUtil
import com.intellij.testFramework.runInEdtAndWait
import com.intellij.xdebugger.XDebuggerUtil
import org.assertj.core.api.Assertions.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.aws.toolkits.jetbrains.core.credentials.MockCredentialsManager
import software.aws.toolkits.jetbrains.settings.SamSettings
import software.aws.toolkits.jetbrains.utils.rules.HeavyJavaCodeInsightTestFixtureRule
import software.aws.toolkits.jetbrains.utils.rules.addClass
import software.aws.toolkits.jetbrains.utils.rules.addModule

class JavaLocalLamdaRunConfigurationIntegrationTest {
    @Rule
    @JvmField
    val projectRule = HeavyJavaCodeInsightTestFixtureRule()

    private val mockId = "MockCredsId"
    private val mockCreds = AwsBasicCredentials.create("Access", "ItsASecret")

    @Before
    fun setUp() {
        SamSettings.getInstance().savedExecutablePath = System.getenv().getOrDefault("SAM_CLI_EXEC", "sam")

        val fixture = projectRule.fixture
        val module = fixture.addModule("main")
        val psiClass = fixture.addClass(
            module,
            """
            package com.example;

            public class LambdaHandler {
                public String handleRequest(String request) {
                    return request.toUpperCase();
                }
            }
            """
        )
        runInEdtAndWait {
            fixture.openFileInEditor(psiClass.containingFile.virtualFile)
        }

        setUpCompiler()

        MockCredentialsManager.getInstance().addCredentials(mockId, mockCreds)
    }

    private fun setUpCompiler() {
        val project = projectRule.project
        val modules = ModuleManager.getInstance(project).modules
        CompilerTestUtil.enableExternalCompiler()

        WriteCommandAction.writeCommandAction(project).run<Nothing> {
            val compilerExtension = CompilerProjectExtension.getInstance(project)!!
            compilerExtension.compilerOutputUrl = projectRule.fixture.tempDirFixture.findOrCreateDir("out").url
            val sdk = JavaAwareProjectJdkTableImpl.getInstanceEx().internalJdk

            for (module in modules) {
                ModuleRootModificationUtil.setModuleSdk(module, sdk)
            }
        }

        runInEdtAndWait {
            PlatformTestUtil.saveProject(project)
            CompilerTestUtil.saveApplicationSettings()
        }
    }

    @After
    fun tearDown() {
        CompilerTestUtil.disableExternalCompiler(projectRule.project)
        MockCredentialsManager.getInstance().reset()
    }

    @Test
    fun samIsExecuted() {
        val runConfiguration = createHandlerBasedRunConfiguration(
            project = projectRule.project,
            input = "\"Hello World\"",
            credentialsProviderId = mockId
        )
        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)
        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")
    }

    @Test
    fun samIsExecutedWhenRunWithATemplateServerless() {
        val templateFile = projectRule.fixture.addFileToProject(
            "template.yaml", """
            Resources:
              SomeFunction:
                Type: AWS::Serverless::Function
                Properties:
                  Handler: com.example.LambdaHandler::handleRequest
                  CodeUri: /some/dummy/code/location
                  Runtime: java8
                  Timeout: 900
        """.trimIndent()
        )

        val runConfiguration = createTemplateRunConfiguration(
            project = projectRule.project,
            templateFile = templateFile.containingFile.virtualFile.path,
            logicalId = "SomeFunction",
            input = "\"Hello World\"",
            credentialsProviderId = mockId
        )

        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)
        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")
    }

    @Test
    fun samIsExecutedWhenRunWithATemplateLambda() {
        val templateFile = projectRule.fixture.addFileToProject(
            "template.yaml", """
            Resources:
              SomeFunction:
                Type: AWS::Lambda::Function
                Properties:
                  Handler: com.example.LambdaHandler::handleRequest
                  Code: /some/dummy/code/location
                  Runtime: java8
                  Timeout: 900
        """.trimIndent()
        )

        val runConfiguration = createTemplateRunConfiguration(
            project = projectRule.project,
            templateFile = templateFile.containingFile.virtualFile.path,
            logicalId = "SomeFunction",
            input = "\"Hello World\"",
            credentialsProviderId = mockId
        )

        assertThat(runConfiguration).isNotNull

        val executeLambda = executeLambda(runConfiguration)
        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")
    }

    @Test
    fun samIsExecutedWithDebugger() {
        runInEdtAndWait {
            val document = projectRule.fixture.editor.document
            val lambdaClass = projectRule.fixture.file as PsiJavaFile
            val lambdaBody = lambdaClass.classes[0].allMethods[0].body!!.statements[0]
            val lineNumber = document.getLineNumber(lambdaBody.textOffset)

            XDebuggerUtil.getInstance().toggleLineBreakpoint(
                projectRule.project,
                projectRule.fixture.file.virtualFile,
                lineNumber
            )
        }

        val runConfiguration = createHandlerBasedRunConfiguration(
            project = projectRule.project,
            input = "\"Hello World\"",
            credentialsProviderId = mockId
        )
        assertThat(runConfiguration).isNotNull

        val debuggerIsHit = checkBreakPointHit(projectRule.project)

        val executeLambda = executeLambda(runConfiguration, DefaultDebugExecutor.EXECUTOR_ID)

        assertThat(executeLambda.exitCode).isEqualTo(0)
        assertThat(executeLambda.stdout).contains("HELLO WORLD")

        assertThat(debuggerIsHit.get()).isTrue()
    }
}
package org.intellij.examples.simple.plugin

import com.intellij.remoterobot.fixtures.ComponentFixture
import com.intellij.remoterobot.fixtures.ContainerFixture
import com.intellij.remoterobot.search.locators.byXpath
import com.intellij.remoterobot.stepsProcessing.step
import com.intellij.remoterobot.utils.autocomplete
import com.intellij.remoterobot.utils.keyboard
import com.intellij.remoterobot.utils.waitFor
import org.assertj.swing.core.MouseButton
import org.intellij.examples.simple.plugin.pages.*
import org.intellij.examples.simple.plugin.pages.DialogFixture.Companion.byTitle
import org.intellij.examples.simple.plugin.utils.StepsLogger
import org.intellij.examples.simple.plugin.utils.uiTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.awt.event.KeyEvent
import java.time.Duration

class CreateCommandLineKotlinTest {
    init {
        StepsLogger.init()
    }

    @AfterEach
    fun closeProject() = uiTest {
        idea {
            actionMenu("File").click()
            actionMenuItem("Close Project").click()
        }
    }

    @Test
    fun createCommandLineApp() = uiTest {
        welcomeFrame {
            createNewProjectLink.click()
            dialog("New Project") {
                findText("Kotlin").click()
                find(
                    ComponentFixture::class.java,
                    byXpath("//div[@class='JBList' and contains(@visible_text,'JVM')]")
                ).findText("JVM | IDEA").click()
                runJs("robot.pressAndReleaseKey(${KeyEvent.VK_SPACE})")
                button("Next").click()
                button("Finish").click()
            }
        }
        step("Close Tip of the Day if it appears") {
            val idea: IdeaFrame = find(IdeaFrame::class.java)
            idea.dumbAware {
                try {
                    idea.find(
                        DialogFixture::class.java,
                        byTitle("Tip of the Day")
                    ).button("Close").click()
                } catch (ignore: Throwable) {
                }
            }
        }
        idea {
            step("Create App file") {
                with(projectViewTree) {
                    findText(projectName).doubleClick()
                    waitFor { hasText("src") }
                    findText("src").click(MouseButton.RIGHT_BUTTON)
                }
                actionMenu("New").click()
                actionMenuItem("Kotlin File/Class").click()
                keyboard { enterText("App"); enter() }
            }
            editor("App.kt") {
                step("Write a code") {
                    autocomplete("main")
                    autocomplete("sout")
                    keyboard { enterText("\""); enterText("Hello from UI test") }
                }
                step("Launch application") {
                    findText("main").click()
                    keyboard { hotKey(KeyEvent.VK_ALT, KeyEvent.VK_ENTER); enter() }
                }
            }

            val consoleLocator = byXpath("ConsoleViewImpl", "//div[@class='ConsoleViewImpl']")
            step("Wait for Console appears") {
                waitFor(Duration.ofMinutes(1)) { findAll<ContainerFixture>(consoleLocator).isNotEmpty() }
            }
            step("Check the message") {
                assert(find<ContainerFixture>(consoleLocator).hasText("Hello from UI test"))
            }
        }
    }
}

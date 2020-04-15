// Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
// SPDX-License-Identifier: Apache-2.0

package software.aws.toolkits.jetbrains.core.credentials

import com.intellij.execution.CommonJavaRunConfigurationParameters
import com.intellij.execution.RunConfigurationExtension
import com.intellij.execution.configurations.JavaParameters
import com.intellij.execution.configurations.RunConfigurationBase
import com.intellij.execution.configurations.RunnerSettings
import com.intellij.openapi.options.SettingsEditor
import com.intellij.openapi.util.Key
import com.intellij.ui.layout.panel
import com.intellij.util.xmlb.XmlSerializer
import org.jdom.Element
import software.aws.toolkits.core.utils.tryOrNull
import software.aws.toolkits.resources.message
import javax.swing.JCheckBox
import javax.swing.JComponent

class RunConfigurationCredentialsExtension : RunConfigurationExtension() {
    override fun isApplicableFor(configuration: RunConfigurationBase<*>): Boolean = configuration is CommonJavaRunConfigurationParameters

    override fun <T : RunConfigurationBase<*>?> updateJavaParameters(configuration: T, params: JavaParameters, runnerSettings: RunnerSettings?) {
        val project = configuration?.getProject() ?: return
        val environment = params.env

        if (configuration.getCopyableUserData(RUN_CONFIGURATION_CREDENTIALS_KEY)?.useCurrentConnection != true) {
            return
        }

        tryOrNull {
            project.activeCredentialProvider()
        }?.resolveCredentials()?.toEnvironmentVariables()?.forEach { key, value -> environment.putIfAbsent(key, value) }

        tryOrNull {
            project.activeRegion()
        }?.toEnvironmentVariables()?.forEach { key, value -> environment.putIfAbsent(key, value) }
    }

    override fun getEditorTitle() = message("aws_connection.tab.label")

    override fun <T : RunConfigurationBase<*>?> createEditor(configuration: T): SettingsEditor<T>? = RunConfigurationCredentialsSettingsEditor()

    override fun readExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        runConfiguration.putCopyableUserData(
            RUN_CONFIGURATION_CREDENTIALS_KEY,
            XmlSerializer.deserialize(element, RunConfigurationCredentialsOptions::class.java)
        )
    }

    override fun writeExternal(runConfiguration: RunConfigurationBase<*>, element: Element) {
        runConfiguration.getCopyableUserData(RUN_CONFIGURATION_CREDENTIALS_KEY)?.let {
            XmlSerializer.serializeInto(it, element)
        }
    }
}

class RunConfigurationCredentialsSettingsEditor<T : RunConfigurationBase<*>?> : SettingsEditor<T>() {
    private lateinit var useCurrentConnection: JCheckBox
    private val panel = panel {
        row {
            useCurrentConnection = checkBox(
                message("run_configuration_extension.inject_aws_connection.label"),
                comment = message("run_configuration_extension.inject_aws_connection.comment")
            ).component
        }
    }

    override fun resetEditorFrom(configuration: T) {
        configuration?.getCopyableUserData(RUN_CONFIGURATION_CREDENTIALS_KEY)?.let { config ->
            useCurrentConnection.isSelected = config.useCurrentConnection
        }
    }

    override fun createEditor(): JComponent = panel

    override fun applyEditorTo(configuration: T) {
        configuration?.putCopyableUserData(
            RUN_CONFIGURATION_CREDENTIALS_KEY,
            RunConfigurationCredentialsOptions().also { it.useCurrentConnection = useCurrentConnection.isSelected })
    }
}

val RUN_CONFIGURATION_CREDENTIALS_KEY = Key.create<RunConfigurationCredentialsOptions>("aws.toolkit.runConfigurationCredentials")

class RunConfigurationCredentialsOptions {
    var useCurrentConnection: Boolean = false
}

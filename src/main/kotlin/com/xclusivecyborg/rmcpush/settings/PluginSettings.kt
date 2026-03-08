package com.xclusivecyborg.rmcpush.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.project.Project

@Service(Service.Level.PROJECT)
@State(
    name = "RmcPushSettings",
    storages = [Storage("rmcPush.xml")]
)
class PluginSettings : PersistentStateComponent<PluginSettings.State> {

    data class State(var serviceAccountPath: String = "")

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        myState = state
    }

    companion object {
        fun getInstance(project: Project): PluginSettings =
            project.getService(PluginSettings::class.java)
    }
}

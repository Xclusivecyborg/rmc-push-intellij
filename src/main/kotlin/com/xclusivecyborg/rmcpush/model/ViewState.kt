package com.xclusivecyborg.rmcpush.model

/**
 * Everything the tool window needs to draw itself.
 *
 * The panel is a pure function of this, which is what lets the IDE create and
 * throw away the component whenever the tool window is opened or closed while
 * the session behind it keeps running.
 */
sealed class ViewState {

    /** No service account configured for this project yet. */
    object NoAccount : ViewState()

    data class Busy(val message: String) : ViewState()

    data class Error(val message: String, val hasAccount: Boolean) : ViewState()

    data class Ready(
        val projectId: String,
        val accountPath: String,
        val sections: List<ConfigSection>
    ) : ViewState()
}

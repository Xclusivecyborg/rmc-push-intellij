package com.xclusivecyborg.rmcpush.model

/**
 * One parameter write: pushed on its own, or held in the batch until the user
 * pushes several at once.
 *
 * Keyed by [key] alone. Firebase requires parameter keys to be unique across the
 * whole template — root and every group — so two changes to the same key can
 * never both be valid.
 */
data class ParameterChange(
    val key: String,
    val value: String,
    val valueType: String,
    /** null for a root parameter. */
    val group: String?,
    /** True when the key does not exist in Firebase yet. */
    val isNew: Boolean
)

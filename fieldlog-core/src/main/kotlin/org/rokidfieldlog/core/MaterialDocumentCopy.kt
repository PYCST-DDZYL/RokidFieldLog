package org.rokidfieldlog.core

import java.io.InputStream
import java.io.OutputStream

/** An unstarted write must never delete a document that the user selected. */
object MaterialDocumentCopy {
    fun copy(validate: () -> Unit, openInput: () -> InputStream, openOutput: () -> OutputStream, deletePartial: () -> Unit) {
        validate()
        var started = false
        try {
            openOutput().use { output ->
                started = true
                openInput().use { it.copyTo(output) }
            }
        } catch (error: Exception) {
            if (started) runCatching(deletePartial)
            throw error
        }
    }
}

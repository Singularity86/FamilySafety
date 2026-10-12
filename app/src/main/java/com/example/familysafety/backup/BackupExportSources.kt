package com.example.familysafety.backup

import com.example.familysafety.group.GroupDefinition
import timber.log.Timber

/**
 * Gathers what a backup needs and, when something is missing, says which thing and why.
 *
 * Export used to report every failure as "no family group or mnemonic found". That covered
 * a phone that never saved its phrase (no backup is possible), a store that couldn't be read
 * for a moment (try again), and anything else, so nobody could tell which one they had hit.
 */
object BackupExportSources {

    sealed class Result {
        data class Ready(val mnemonic: List<String>, val family: GroupDefinition) : Result()
        data class Missing(val message: String) : Result()
    }

    const val NO_PHRASE_SAVED =
        "This phone never saved your recovery phrase, so it can't make a backup. If you have " +
            "your 12 words written down, those already restore your account."
    const val PHRASE_UNREADABLE =
        "Your recovery phrase can't be read on this phone right now. Nothing has been " +
            "deleted. Try again in a moment, or restart your phone."
    const val FAMILY_UNREADABLE =
        "Your family's details can't be read on this phone right now, so the backup would " +
            "be incomplete. Nothing has been deleted. Try again in a moment, or restart your phone."
    const val NO_FAMILY = "This phone isn't in a family yet, so there's nothing to back up."

    fun fileNotSaved(reason: String?) =
        "The backup file couldn't be saved (${reason ?: "unknown error"}). Check the phone " +
            "has free space and try again."

    suspend fun gather(
        readPhrase: () -> List<String>?,
        readFamily: suspend () -> GroupDefinition?
    ): Result {
        val mnemonic = try {
            readPhrase()
        } catch (e: Exception) {
            Timber.e(e, "Backup export: recovery phrase unreadable")
            return Result.Missing(PHRASE_UNREADABLE)
        }
        if (mnemonic.isNullOrEmpty()) {
            Timber.w("Backup export: no recovery phrase saved on this phone")
            return Result.Missing(NO_PHRASE_SAVED)
        }

        val family = try {
            readFamily()
        } catch (e: Exception) {
            Timber.e(e, "Backup export: family unreadable")
            return Result.Missing(FAMILY_UNREADABLE)
        } ?: run {
            Timber.w("Backup export: no family saved on this phone")
            return Result.Missing(NO_FAMILY)
        }

        return Result.Ready(mnemonic, family)
    }
}

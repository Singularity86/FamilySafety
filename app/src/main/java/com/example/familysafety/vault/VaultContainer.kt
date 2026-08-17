package com.example.familysafety.vault

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.security.SecureRandom

/**
 * The container on disk: one fixed-size file that always exists.
 *
 * It is created full of random bytes the first time the app runs and never changes size, so
 * its presence says nothing about whether anyone has a vault and its size says nothing about
 * what is in one. There is no header, no version byte and no magic number — anything of that
 * kind would be a marker saying "a vault feature was here", and on a phone that someone is
 * being made to unlock, a marker is the whole problem.
 *
 * Writes go through a mutex and land via a scratch file and a rename, because a container
 * half-written when the process dies is a family's documents gone.
 */
class VaultContainer(
    private val fileProvider: () -> File
) {
    private val lock = Mutex()

    /**
     * Read the container, creating it from random bytes if this is the first run.
     *
     * A container of the wrong length — truncated by a failed write on a build before the
     * rename, or corrupted — is replaced rather than repaired. There is nothing to repair
     * towards: without a key, no part of it can be told from any other part.
     */
    suspend fun read(): ByteArray = withContext(Dispatchers.IO) {
        lock.withLock { readLocked() }
    }

    private fun readLocked(): ByteArray {
        val file = fileProvider()
        if (file.exists() && file.length() == VaultSlots.CONTAINER_BYTES.toLong()) {
            return runCatching { file.readBytes() }.getOrElse { fresh() }
        }
        if (file.exists()) {
            Timber.w("Vault container was ${file.length()} bytes; replacing")
        }
        return fresh().also { writeLocked(it) }
    }

    private fun fresh(): ByteArray = VaultSlots.randomContainer(SecureRandom())

    /**
     * Replace the container.
     *
     * Refuses anything that is not exactly the right length, so a bug upstream cannot shrink
     * the container and leak that fewer slots are in use.
     */
    suspend fun write(container: ByteArray): Boolean = withContext(Dispatchers.IO) {
        require(container.size == VaultSlots.CONTAINER_BYTES) {
            "container is ${container.size} bytes, must be ${VaultSlots.CONTAINER_BYTES}"
        }
        lock.withLock { writeLocked(container) }
    }

    private fun writeLocked(container: ByteArray): Boolean = runCatching {
        val file = fileProvider()
        file.parentFile?.mkdirs()
        val scratch = File(file.parentFile, "${file.name}.part")
        scratch.writeBytes(container)
        if (file.exists() && !file.delete()) {
            Timber.e("Could not clear the old vault container")
            scratch.delete()
            return@runCatching false
        }
        scratch.renameTo(file)
    }.getOrElse {
        Timber.e(it, "Could not write the vault container")
        false
    }

    /**
     * Apply [mutate] to the container and store the result, under one lock.
     *
     * Read-modify-write has to be atomic here or two additions racing lose one of them, and
     * because a slot is only meaningful as a whole, a lost write is a lost document rather
     * than a stale field.
     */
    suspend fun update(mutate: (ByteArray) -> ByteArray?): ByteArray? = withContext(Dispatchers.IO) {
        lock.withLock {
            val current = readLocked()
            val next = mutate(current) ?: return@withLock null
            require(next.size == VaultSlots.CONTAINER_BYTES) {
                "mutation produced ${next.size} bytes"
            }
            if (writeLocked(next)) next else null
        }
    }
}

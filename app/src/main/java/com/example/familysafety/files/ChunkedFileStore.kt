package com.example.familysafety.files

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * On-disk storage for a file that is still arriving, plus the streaming paths that replaced
 * whole-file buffering.
 *
 * ## Why a single sparse blob
 *
 * In-flight chunks used to be one small file each under `<fileId>/.tmp/chunk_<i>`, and
 * "progress" was a directory listing. Two problems followed from that: a count of files says
 * nothing about *which* indices are held, and the chunks were stored decrypted, so a device
 * holding a half-downloaded document held it in plaintext.
 *
 * Here every chunk occupies a fixed slot in one file, so chunk *i* lives at `i * stride`.
 * Slots hold the **wire-format ciphertext verbatim** — nonce, ciphertext and GCM tag exactly
 * as they arrived. That has three consequences worth stating:
 *
 * - a partially downloaded document is never on disk in the clear;
 * - repair can read slot *i* and retransmit it byte-for-byte, with no re-encryption and
 *   without ever materialising plaintext;
 * - a never-written slot is zeros, which fails GCM authentication, so the blob can rebuild
 *   its own accounting after a crash without trusting the database.
 *
 * The last point is why the bitmap is treated as a cache and the blob as the truth.
 *
 * ## Concurrency
 *
 * Chunks arrive on many coroutines at once. All writes for one file go through a per-file
 * [Mutex], so the blob and its bitmap always move together; without that, two concurrent
 * read-modify-write cycles on the bitmap silently lose bits.
 */
class ChunkedFileStore(
    private val rootDir: () -> File
) {
    private val locks = ConcurrentHashMap<String, Mutex>()

    private fun lockFor(fileId: String): Mutex = locks.computeIfAbsent(fileId) { Mutex() }

    private fun fileDir(fileId: String): File = File(rootDir(), fileId).also { it.mkdirs() }

    fun blobFile(fileId: String): File = File(fileDir(fileId), BLOB_NAME)

    fun contentFile(fileId: String): File = File(fileDir(fileId), CONTENT_NAME)

    /** Legacy per-chunk directory from before the blob store; swept on startup. */
    fun legacyTempDir(fileId: String): File = File(fileDir(fileId), ".tmp")

    /**
     * Bytes one chunk occupies on disk: the plaintext chunk plus GCM's nonce and tag.
     * Every slot is this wide except the last, which is short and simply leaves a gap.
     */
    fun strideFor(chunkSize: Int, nonceBytes: Int, tagBits: Int): Int =
        nonceBytes + chunkSize + tagBits / 8

    /**
     * Write one chunk's ciphertext into its slot and return the updated bitmap.
     *
     * [ciphertext] must be exactly what arrived on the wire. Returns null if the write could
     * not be completed, leaving the bitmap untouched so the chunk is re-requested later.
     */
    suspend fun writeChunk(
        fileId: String,
        chunkIndex: Int,
        chunkCount: Int,
        stride: Int,
        ciphertext: ByteArray,
        currentBitmap: ByteArray?
    ): ByteArray? = withContext(Dispatchers.IO) {
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            Timber.w("Chunk $chunkIndex out of range for $fileId ($chunkCount chunks)")
            return@withContext null
        }
        if (ciphertext.size > stride) {
            Timber.w("Chunk $chunkIndex of $fileId is ${ciphertext.size} bytes, over stride $stride")
            return@withContext null
        }
        lockFor(fileId).withLock {
            try {
                RandomAccessFile(blobFile(fileId), "rw").use { raf ->
                    raf.seek(chunkIndex.toLong() * stride)
                    raf.write(ciphertext)
                    // The final chunk is shorter than a stride. Recording its true length
                    // lets the reader hand back exactly the bytes that arrived rather than
                    // a slot padded with whatever was there before.
                    if (ciphertext.size < stride) {
                        writeShortLength(fileId, chunkIndex, ciphertext.size)
                    }
                }
                ChunkBitmap.withBitSet(currentBitmap, chunkIndex, chunkCount)
            } catch (e: Exception) {
                Timber.e(e, "Failed to write chunk $chunkIndex of $fileId")
                null
            }
        }
    }

    /**
     * Read back a chunk's ciphertext exactly as it was received, for retransmission.
     * Returns null when the slot was never written or cannot be read.
     */
    suspend fun readChunk(
        fileId: String,
        chunkIndex: Int,
        stride: Int
    ): ByteArray? = withContext(Dispatchers.IO) {
        lockFor(fileId).withLock {
            try {
                val blob = blobFile(fileId)
                if (!blob.exists()) return@withLock null
                val offset = chunkIndex.toLong() * stride
                if (offset >= blob.length()) return@withLock null
                val length = readShortLength(fileId, chunkIndex)
                    ?: minOf(stride.toLong(), blob.length() - offset).toInt()
                val buf = ByteArray(length)
                RandomAccessFile(blob, "r").use { raf ->
                    raf.seek(offset)
                    raf.readFully(buf)
                }
                buf
            } catch (e: Exception) {
                Timber.d("Could not read chunk $chunkIndex of $fileId: ${e.message}")
                null
            }
        }
    }

    /**
     * Stream the blob out as plaintext, decrypting slot by slot, and hash it as it goes.
     *
     * Nothing whole-file is held in memory: peak usage is one chunk. Returns the SHA-256 hex
     * of the plaintext so the caller can compare it against the manifest without a second
     * pass over the data.
     */
    suspend fun decryptTo(
        fileId: String,
        chunkCount: Int,
        stride: Int,
        out: OutputStream,
        decryptChunk: (ByteArray) -> ByteArray
    ): String? = withContext(Dispatchers.IO) {
        lockFor(fileId).withLock {
            try {
                val digest = MessageDigest.getInstance("SHA-256")
                RandomAccessFile(blobFile(fileId), "r").use { raf ->
                    for (i in 0 until chunkCount) {
                        val offset = i.toLong() * stride
                        if (offset >= raf.length()) return@withLock null
                        val length = readShortLength(fileId, i)
                            ?: minOf(stride.toLong(), raf.length() - offset).toInt()
                        val slot = ByteArray(length)
                        raf.seek(offset)
                        raf.readFully(slot)
                        val plain = decryptChunk(slot)
                        digest.update(plain)
                        out.write(plain)
                    }
                }
                out.flush()
                digest.digest().joinToString("") { "%02x".format(it) }
            } catch (e: Exception) {
                Timber.e(e, "Failed to decrypt blob for $fileId")
                null
            }
        }
    }

    /**
     * Rebuild the bitmap from the blob by testing which slots authenticate.
     *
     * Used when the stored bitmap is missing or the wrong length — after an upgrade, or if the
     * process died between writing a chunk and committing the row. An unwritten slot is zeros
     * and fails the GCM tag, so authentication is an exact test for "this chunk is really
     * here", which is what makes the database and the filesystem self-reconciling without a
     * transaction spanning both.
     */
    suspend fun rebuildBitmap(
        fileId: String,
        chunkCount: Int,
        stride: Int,
        decryptChunk: (ByteArray) -> ByteArray
    ): ByteArray = withContext(Dispatchers.IO) {
        lockFor(fileId).withLock {
            var bitmap = ChunkBitmap.empty(chunkCount)
            val blob = blobFile(fileId)
            if (!blob.exists()) return@withLock bitmap
            try {
                RandomAccessFile(blob, "r").use { raf ->
                    for (i in 0 until chunkCount) {
                        val offset = i.toLong() * stride
                        if (offset >= raf.length()) break
                        val length = readShortLength(fileId, i)
                            ?: minOf(stride.toLong(), raf.length() - offset).toInt()
                        if (length <= 0) continue
                        val slot = ByteArray(length)
                        raf.seek(offset)
                        raf.readFully(slot)
                        val ok = runCatching { decryptChunk(slot) }.isSuccess
                        if (ok) bitmap = ChunkBitmap.withBitSet(bitmap, i, chunkCount)
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Partial bitmap rebuild for $fileId")
            }
            bitmap
        }
    }

    /**
     * Copy [input] to [target] in fixed buffers, hashing as it goes.
     *
     * This is what replaced `contentResolver.openInputStream(uri)?.use { it.readBytes() }`
     * followed by `bytes.toList().chunked(...)`. That path held the whole file in memory
     * several times over and, because `chunked` builds a `List<List<Byte>>` of boxed
     * references before rematerialising every chunk, cost several times the file size again
     * in transient reference arrays. Peak usage here is one buffer.
     */
    suspend fun copyAndHash(
        input: InputStream,
        target: File,
        bufferSize: Int
    ): Pair<String, Long> = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(bufferSize)
        var total = 0L
        target.parentFile?.mkdirs()
        target.outputStream().buffered().use { out ->
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
                out.write(buffer, 0, read)
                total += read
            }
            out.flush()
        }
        digest.digest().joinToString("") { "%02x".format(it) } to total
    }

    /** Drop the in-flight blob once the file is complete, or when giving up on it. */
    suspend fun discardBlob(fileId: String) = withContext(Dispatchers.IO) {
        lockFor(fileId).withLock {
            runCatching { blobFile(fileId).delete() }
            runCatching { shortLengthFile(fileId).delete() }
            Unit
        }
    }

    /**
     * Free space on the volume holding the library, or 0 if it cannot be determined.
     *
     * Checked before staging an upload: external storage on modern Android goes through FUSE
     * and may allocate eagerly rather than honouring sparseness, so running out mid-copy is a
     * real outcome and a clear refusal beats a half-written file.
     */
    fun usableSpaceBytes(): Long = runCatching { rootDir().usableSpace }.getOrDefault(0L)

    fun deleteAll(fileId: String) {
        locks.remove(fileId)
        runCatching { File(rootDir(), fileId).deleteRecursively() }
    }

    /**
     * Remove `.tmp` directories left by the pre-blob layout.
     *
     * Their contents were plaintext chunks, and nothing references them any more — the chunks
     * they hold will simply be re-requested. Previously these leaked on every hash mismatch,
     * because cleanup only ran on the success path.
     */
    fun sweepLegacyTempDirs(): Int {
        val root = rootDir()
        val dirs = root.listFiles()?.filter { it.isDirectory } ?: return 0
        var removed = 0
        dirs.forEach { dir ->
            val tmp = File(dir, ".tmp")
            if (tmp.exists() && tmp.deleteRecursively()) removed++
        }
        return removed
    }

    // The last chunk is shorter than a stride, and a slot cannot record its own length. This
    // keeps a tiny sidecar of "index:length" lines rather than widening the wire format.
    private fun shortLengthFile(fileId: String) = File(fileDir(fileId), SHORT_LEN_NAME)

    private fun writeShortLength(fileId: String, chunkIndex: Int, length: Int) {
        runCatching {
            val f = shortLengthFile(fileId)
            val existing = if (f.exists()) f.readLines().filterNot {
                it.startsWith("$chunkIndex:")
            } else emptyList()
            f.writeText((existing + "$chunkIndex:$length").joinToString("\n"))
        }
    }

    private fun readShortLength(fileId: String, chunkIndex: Int): Int? = runCatching {
        val f = shortLengthFile(fileId)
        if (!f.exists()) return null
        f.readLines()
            .firstOrNull { it.startsWith("$chunkIndex:") }
            ?.substringAfter(':')
            ?.toIntOrNull()
    }.getOrNull()

    companion object {
        const val BLOB_NAME = "blob.enc"
        const val CONTENT_NAME = "content"
        private const val SHORT_LEN_NAME = "slots.idx"
    }
}

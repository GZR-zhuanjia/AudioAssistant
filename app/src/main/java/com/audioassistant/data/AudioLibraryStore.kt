package com.audioassistant.data

import com.audioassistant.domain.AudioLibraryItem
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.ConcurrentHashMap
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * A metadata-only library. Source files may still belong to projects after their
 * library entries are removed. Repository callers provide stable content IDs;
 * putting the same ID replaces that entry without creating a duplicate.
 */
class AudioLibraryStore(directory: File) {
    private val directory = directory.canonicalFile
    private val lock = locks.computeIfAbsent(this.directory.path) { Any() }
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true; prettyPrint = true }

    @Serializable
    private data class Record(val schemaVersion: Int, val item: AudioLibraryItem)

    fun list(): List<AudioLibraryItem> = synchronized(lock) {
        ensureDirectory()
        val files = directory.listFiles()
            ?: throw IOException("音频库目录无法读取，请检查存储空间后重试：${directory.path}")
        files.filter { it.name.endsWith(".json") }
            .map { file ->
                val id = file.name.removeSuffix(".json")
                if (!validId.matches(id)) {
                    throw IOException("音频库记录文件名无效，原始文件已保留，请先备份：${file.name}")
                }
                read(metadataFile(id), id)
            }
            .sortedWith(compareByDescending<AudioLibraryItem> { it.createdAtMs }.thenBy { it.id })
    }

    fun put(item: AudioLibraryItem): Unit = synchronized(lock) {
        val destination = metadataFile(item.id)
        ensureDirectory()
        // Never turn an unreadable/future record into a successful empty upsert.
        if (destination.exists()) read(destination, item.id)
        val encoded = json.encodeToString(Record(SCHEMA_VERSION, item)).toByteArray(Charsets.UTF_8)
        var temporary: File? = null
        try {
            temporary = File.createTempFile(".${item.id}-", ".partial", directory)
            FileOutputStream(temporary).use { output ->
                output.write(encoded)
                output.flush()
                output.fd.sync()
            }
            Files.move(
                temporary.toPath(), destination.toPath(),
                StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            throw IOException("音频库记录 ${destination.name} 保存失败，已有记录已保留，请检查可用空间后重试", e)
        } finally {
            temporary?.delete()
        }
    }

    /** Remove only JSON metadata. Never remove or alter the referenced audio. */
    fun remove(id: String): Unit = synchronized(lock) {
        val file = metadataFile(id)
        ensureDirectory()
        if (file.exists()) {
            read(file, id)
            if (!file.delete()) throw IOException("音频库记录 ${file.name} 删除失败，请检查存储空间后重试")
        }
    }

    private fun read(file: File, expectedId: String): AudioLibraryItem {
        val record = try {
            json.decodeFromString<Record>(file.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            throw IOException("音频库记录 ${file.name} 无法读取，原始文件已保留，请先备份后重试", e)
        }
        if (record.schemaVersion != SCHEMA_VERSION) {
            throw IOException("音频库记录 ${file.name} 的版本 ${record.schemaVersion} 暂不支持，原始文件已保留，请升级应用或先备份")
        }
        if (record.item.id != expectedId) {
            throw IOException("音频库记录 ${file.name} 的 ID 与文件名不符，原始文件已保留，请先备份后重试")
        }
        return record.item
    }

    private fun metadataFile(id: String): File {
        require(validId.matches(id)) { "无效音频库 ID" }
        val file = File(directory, "$id.json")
        if (Files.isSymbolicLink(file.toPath()) || file.canonicalFile.parentFile != directory) {
            throw IOException("音频库记录路径无效，原始文件已保留：${file.name}")
        }
        return file
    }

    private fun ensureDirectory() {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("音频库目录无法创建，请检查存储空间后重试：${directory.path}")
        }
    }

    companion object {
        private const val SCHEMA_VERSION = 1
        private val validId = Regex("[A-Za-z0-9][A-Za-z0-9_-]{0,127}")
        /** All store instances for the same canonical directory share one process lock. */
        private val locks = ConcurrentHashMap<String, Any>()
    }
}

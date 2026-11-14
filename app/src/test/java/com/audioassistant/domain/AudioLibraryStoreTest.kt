package com.audioassistant.domain

import com.audioassistant.data.AudioLibraryStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AudioLibraryStoreTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }

    private fun item(id: String = "content-hash", createdAtMs: Long = 1_000) = AudioLibraryItem(
        id = id,
        source = SourceAudio(
            id = "source-$id", originalUri = "content://audio/42", localPath = "/private/音乐/source.mp3",
            displayName = "演奏会 🎵.mp3", durationUs = 9_007_199_254_740_993,
            fingerprint = "sha256-$id", sampleRate = 44_100, channels = 1,
        ),
        createdAtMs = createdAtMs,
    )

    @Test fun freshLibraryIsEmptyAndCreatesItsPrivateMetadataDirectory() {
        val directory = File(temporary.root, "library")
        assertEquals(emptyList<AudioLibraryItem>(), AudioLibraryStore(directory).list())
        assertTrue(directory.isDirectory)
    }

    @Test fun reloadPreservesSourcePrecisionFormatsOriginAndUnicode() {
        val directory = temporary.newFolder("library")
        val converted = item()
        val imported = item("imported", 2_000).copy(origin = LibraryOrigin.AUDIO_IMPORT)
        val legacy = item("legacy", 3_000).copy(origin = LibraryOrigin.LEGACY_OUTPUT)
        val store = AudioLibraryStore(directory)
        listOf(converted, imported, legacy).forEach(store::put)
        assertEquals(listOf(legacy, imported, converted), AudioLibraryStore(directory).list())
        assertEquals(LibraryOrigin.VIDEO_CONVERSION, converted.origin)
        assertEquals(converted, json.decodeFromString<AudioLibraryItem>(json.encodeToString(converted)))
        assertTrue(File(directory, "${converted.id}.json").readText().contains("\"schemaVersion\": 1"))
    }

    @Test fun repeatedContentIdUpsertsOneEntryInsteadOfDuplicatingIt() {
        val directory = temporary.newFolder("library")
        val original = item()
        val renamed = original.copy(source = original.source.copy(displayName = "Renamed.mp3"))
        val store = AudioLibraryStore(directory)
        store.put(original)
        AudioLibraryStore(directory).put(renamed)
        assertEquals(listOf(renamed), store.list())
        assertEquals(listOf("${original.id}.json"), directory.listFiles()!!.map { it.name })
    }

    @Test fun recentEntriesAppearFirstWithStableOrderForEqualTimestamps() {
        val store = AudioLibraryStore(temporary.newFolder("library"))
        val first = item("a", 3_000)
        val second = item("b", 3_000)
        val older = item("old", 100)
        listOf(second, older, first).forEach(store::put)
        assertEquals(listOf(first, second, older), store.list())
    }

    @Test fun removalDeletesOnlyMetadataAndLeavesAudioAvailableToProjects() {
        val directory = temporary.newFolder("library")
        val audio = temporary.newFile("shared-source.mp3")
        val bytes = byteArrayOf(73, 68, 51, 1, 2, 3)
        audio.writeBytes(bytes)
        val entry = item().let { it.copy(source = it.source.copy(localPath = audio.absolutePath)) }
        val store = AudioLibraryStore(directory)
        store.put(entry)
        store.remove(entry.id)
        store.remove(entry.id)
        assertEquals(emptyList<AudioLibraryItem>(), AudioLibraryStore(directory).list())
        assertTrue(audio.isFile)
        assertArrayEquals(bytes, audio.readBytes())
    }

    @Test fun corruptMetadataIsPreservedAndCannotBeSilentlyOverwrittenOrRemoved() {
        val directory = temporary.newFolder("library")
        val entry = item()
        val file = File(directory, "${entry.id}.json")
        val broken = "{ interrupted library record"
        file.writeText(broken)
        val store = AudioLibraryStore(directory)
        for (operation in listOf<() -> Unit>({ store.list() }, { store.put(entry) }, { store.remove(entry.id) })) {
            val failure = assertThrows(IOException::class.java, operation)
            assertTrue(failure.message!!.contains(file.name))
            assertTrue(failure.message!!.contains("已保留"))
            assertEquals(broken, file.readText())
        }
    }

    @Test fun unsupportedSchemaPreservesTheFutureRecordForRecovery() {
        val directory = temporary.newFolder("library")
        val entry = item()
        val file = File(directory, "${entry.id}.json")
        val future = """{"schemaVersion":99,"item":${json.encodeToString(entry)}}"""
        file.writeText(future)
        val store = AudioLibraryStore(directory)
        assertTrue(assertThrows(IOException::class.java) { store.list() }.message!!.contains("99"))
        assertThrows(IOException::class.java) { store.put(entry) }
        assertThrows(IOException::class.java) { store.remove(entry.id) }
        assertEquals(future, file.readText())
    }

    @Test fun recordIdMustMatchItsFilenameBeforeUseOrReplacement() {
        val directory = temporary.newFolder("library")
        val file = File(directory, "expected.json")
        val mismatched = """{"schemaVersion":1,"item":${json.encodeToString(item("different"))}}"""
        file.writeText(mismatched)
        val store = AudioLibraryStore(directory)
        assertThrows(IOException::class.java) { store.list() }
        assertThrows(IOException::class.java) { store.put(item("expected")) }
        assertThrows(IOException::class.java) { store.remove("expected") }
        assertEquals(mismatched, file.readText())
    }

    @Test fun unsafeIdsCannotEscapeOrSelectAnotherMetadataPath() {
        val directory = temporary.newFolder("library")
        val store = AudioLibraryStore(directory)
        for (id in listOf("", ".", "..", "../escape", "nested/item", "nested\\item", "/absolute", "has.dot", "a:b", "a\nb", "a".repeat(129))) {
            assertThrows("put $id", IllegalArgumentException::class.java) { store.put(item(id)) }
            assertThrows("remove $id", IllegalArgumentException::class.java) { store.remove(id) }
        }
        assertTrue(directory.listFiles()!!.isEmpty())
    }

    @Test fun symbolicMetadataPathsCannotReadOverwriteOrRemoveAnOutsideFile() {
        val directory = temporary.newFolder("library")
        val outside = temporary.newFile("outside.json")
        val bytes = "outside record".toByteArray()
        outside.writeBytes(bytes)
        val entry = item()
        val link = File(directory, "${entry.id}.json")
        Files.createSymbolicLink(link.toPath(), outside.toPath())
        val store = AudioLibraryStore(directory)
        assertThrows(IOException::class.java) { store.list() }
        assertThrows(IOException::class.java) { store.put(entry) }
        assertThrows(IOException::class.java) { store.remove(entry.id) }
        assertTrue(Files.isSymbolicLink(link.toPath()))
        assertArrayEquals(bytes, outside.readBytes())
    }

    @Test fun interruptedTemporaryWritesDoNotBecomeLibraryEntries() {
        val directory = temporary.newFolder("library")
        val entry = item()
        val store = AudioLibraryStore(directory)
        store.put(entry)
        val partial = File(directory, ".content-hash-interrupted.partial")
        partial.writeText("partial JSON")
        assertEquals(listOf(entry), AudioLibraryStore(directory).list())
        assertTrue(partial.exists())
    }

    @Test fun separateStoreInstancesSerializeConcurrentReadersWritersAndRemovals() {
        val directory = temporary.newFolder("library")
        val first = AudioLibraryStore(directory)
        val second = AudioLibraryStore(File(directory, "."))
        val executor = Executors.newFixedThreadPool(3)
        val start = CountDownLatch(1)
        try {
            val writerOne = executor.submit {
                start.await()
                repeat(30) { first.put(item(createdAtMs = it.toLong())); first.remove("content-hash") }
            }
            val writerTwo = executor.submit {
                start.await()
                repeat(30) { second.put(item(createdAtMs = it.toLong() + 1_000)) }
            }
            val reader = executor.submit {
                start.await()
                repeat(100) { assertTrue(first.list().size <= 1) }
            }
            start.countDown()
            listOf(writerOne, writerTwo, reader).forEach { it.get(20, TimeUnit.SECONDS) }
            second.put(item())
            assertEquals(listOf(item()), first.list())
            assertFalse(directory.listFiles()!!.any { it.name.endsWith(".partial") })
        } finally {
            executor.shutdownNow()
        }
    }

    @Test fun anUnreadableDirectoryIsAnErrorRatherThanAnEmptyLibrary() {
        val file = temporary.newFile("not-a-directory")
        val store = AudioLibraryStore(file)
        assertThrows(IOException::class.java) { store.list() }
        assertThrows(IOException::class.java) { store.put(item()) }
    }
}

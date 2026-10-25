package app.snapcast.data.util

import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class AtomicFilesTest {
    @get:Rule
    val folder = TemporaryFolder()

    @Test
    fun writesAndReplacesContentWithoutLeavingTempFiles() {
        val target = File(folder.root, "tabs.json")
        AtomicFiles.writeText(target, "first")
        assertThat(target.readText()).isEqualTo("first")

        AtomicFiles.writeText(target, "second ✓")
        assertThat(target.readText()).isEqualTo("second ✓")
        assertThat(folder.root.list()!!.toList()).containsExactly("tabs.json")
    }

    @Test
    fun createsMissingParentDirectories() {
        val target = File(folder.root, "a/b/state.json")
        AtomicFiles.writeText(target, "[]")
        assertThat(target.readText()).isEqualTo("[]")
    }
}

package app.snapcast.addons.install

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class InstallSourcesTest {

    @Test
    fun `remote urls`() {
        assertThat(InstallSources.kindOf("https://addons.mozilla.org/a.xpi")).isEqualTo(InstallSourceKind.REMOTE)
        assertThat(InstallSources.kindOf("HTTP://example.com/a.xpi")).isEqualTo(InstallSourceKind.REMOTE)
    }

    @Test
    fun `local sources`() {
        assertThat(InstallSources.kindOf("file:///data/user/0/app/cache/a.xpi")).isEqualTo(InstallSourceKind.FILE)
        assertThat(InstallSources.kindOf("content://com.android.providers.downloads.documents/document/42"))
            .isEqualTo(InstallSourceKind.CONTENT)
    }

    @Test
    fun `everything else is unsupported`() {
        assertThat(InstallSources.kindOf("javascript:void(0)")).isEqualTo(InstallSourceKind.UNSUPPORTED)
        assertThat(InstallSources.kindOf("data:application/x-xpinstall;base64,AA==")).isEqualTo(InstallSourceKind.UNSUPPORTED)
        assertThat(InstallSources.kindOf("https:example.com")).isEqualTo(InstallSourceKind.UNSUPPORTED)
        assertThat(InstallSources.kindOf("addon.xpi")).isEqualTo(InstallSourceKind.UNSUPPORTED)
        assertThat(InstallSources.kindOf("")).isEqualTo(InstallSourceKind.UNSUPPORTED)
    }
}

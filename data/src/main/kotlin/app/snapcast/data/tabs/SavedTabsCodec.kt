package app.snapcast.data.tabs

import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** JSON form of the saved tab list. Anything unreadable decodes to an empty list. */
internal object SavedTabsCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(SavedTab.serializer())

    fun encode(tabs: List<SavedTab>): String = json.encodeToString(serializer, tabs)

    fun decode(text: String): List<SavedTab> {
        if (text.isBlank()) return emptyList()
        return try {
            json.decodeFromString(serializer, text)
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }
}

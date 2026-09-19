package tachiyomi.domain.translation.model

import kotlinx.serialization.Serializable

/** Application content preference; it never edits saved source text or region geometry. */
@Serializable
data class TranslationContentPolicy(val ignoreSoundEffects: Boolean = true) {
    fun excludes(type: String): Boolean = ignoreSoundEffects && isSoundEffect(type)

    fun excludes(region: TextRegion): Boolean = excludes(region.type)

    companion object {
        val Legacy = TranslationContentPolicy(ignoreSoundEffects = false)

        fun isSoundEffect(type: String): Boolean = type.trim().lowercase() in setOf(
            "sfx",
            "sound_effect",
            "sound_effects",
            "sound-effect",
            "sound-effects",
            "sound effect",
            "sound effects",
        )
    }
}

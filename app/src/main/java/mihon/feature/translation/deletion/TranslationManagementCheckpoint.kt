package mihon.feature.translation.deletion

/** Process-local generation lease. A newer Resume, edit, Undo or review invalidates it. */
class TranslationManagementCheckpoint internal constructor(
    internal val owner: Any,
    internal val generations: Map<String, Long>,
) {
    val jobIds: Set<String> get() = generations.keys
}

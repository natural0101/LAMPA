package top.rootu.lampa

enum class NextEpisodeMode(val persistedValue: String) {
    ASK("ask"),
    AUTO("auto"),
    OFF("off");

    companion object {
        fun fromPersistedValue(value: String?): NextEpisodeMode =
            values().firstOrNull { it.persistedValue == value } ?: ASK
    }
}

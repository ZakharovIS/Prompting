package ru.zis.prompting.agent

enum class ContextStrategy(val sessionId: Int, val title: String) {
    SLIDING_WINDOW(0, "Sliding Window"),
    STICKY_FACTS(1, "Sticky Facts"),
    BRANCHING(2, "Branching");

    companion object {
        fun fromSessionId(id: Int): ContextStrategy =
            entries.firstOrNull { it.sessionId == id } ?: SLIDING_WINDOW

        fun fromName(name: String?): ContextStrategy =
            entries.firstOrNull { it.name == name } ?: SLIDING_WINDOW
    }
}

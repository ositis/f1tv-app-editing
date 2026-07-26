package fr.groggy.racecontrol.tv.f1tv

/**
 * Championship / content series used for browse filtering and card badges.
 */
enum class RacingSeries(val preferenceValue: String, val badge: String) {
    ALL("ALL", "ALL"),
    F1("F1", "F1"),
    F2("F2", "F2"),
    F3("F3", "F3"),
    F1_ACADEMY("F1A", "F1A"),
    PORSCHE("PSC", "PSC");

    companion object {
        fun fromPreference(value: String?): RacingSeries =
            entries.firstOrNull { it.preferenceValue.equals(value, ignoreCase = true) } ?: ALL

        /**
         * Classify a session from API fields + title heuristics.
         * Prefer [uiSeries], then EMF [series], then title keywords.
         */
        fun classify(
            uiSeries: String?,
            series: String?,
            title: String
        ): RacingSeries {
            normalizeUiSeries(uiSeries)?.let { return it }
            normalizeSeriesLabel(series)?.let { return it }
            return classifyFromTitle(title)
        }

        private fun normalizeUiSeries(raw: String?): RacingSeries? {
            val value = raw?.trim()?.uppercase() ?: return null
            return when (value) {
                "F1", "FORMULA1", "FORMULA 1" -> F1
                "F2", "FORMULA2", "FORMULA 2" -> F2
                "F3", "FORMULA3", "FORMULA 3" -> F3
                "F1A", "F1 ACADEMY", "ACADEMY" -> F1_ACADEMY
                "PSC", "PORSCHE", "PORSCHE SUPER CUP", "SUPER CUP" -> PORSCHE
                else -> null
            }
        }

        private fun normalizeSeriesLabel(raw: String?): RacingSeries? {
            val value = raw?.trim()?.uppercase() ?: return null
            return when {
                value.contains("ACADEMY") -> F1_ACADEMY
                value.contains("PORSCHE") || value.contains("SUPER CUP") || value == "PSC" -> PORSCHE
                value.contains("FORMULA 3") || value.contains("FORMULA3") || value == "F3" -> F3
                value.contains("FORMULA 2") || value.contains("FORMULA2") || value == "F2" -> F2
                value.contains("FORMULA 1") || value.contains("FORMULA1") || value == "F1" -> F1
                else -> null
            }
        }

        private fun classifyFromTitle(title: String): RacingSeries {
            val t = title.uppercase()
            return when {
                "F1 ACADEMY" in t || "ACADEMY" in t && "F1" in t -> F1_ACADEMY
                "PORSCHE" in t || "SUPER CUP" in t || "SUPER CUP" in t || "SUPERCUP" in t -> PORSCHE
                Regex("""\bF3\b""").containsMatchIn(t) || "FORMULA 3" in t -> F3
                Regex("""\bF2\b""").containsMatchIn(t) || "FORMULA 2" in t -> F2
                else -> F1
            }
        }
    }
}

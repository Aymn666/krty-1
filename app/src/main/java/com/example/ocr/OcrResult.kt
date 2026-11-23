package com.example.ocr

sealed interface OcrScanState {
    data class Scanning(
        val candidateCode: String? = null,
        val votes: Int = 0,
        val requiredVotes: Int = 3,
        val isLowLight: Boolean = false,
        val statusMessage: String = "وجّه الكاميرا نحو رقم الكرت"
    ) : OcrScanState

    data class Locked(
        val code: String,
        val confidence: Float
    ) : OcrScanState

    data class Error(
        val message: String
    ) : OcrScanState
}

data class CandidateObservation(
    val text: String,
    val normalizedHeight: Float,
    val inCenterRoi: Boolean,
    val mlKitConfidence: Float
)

/**
 * Pure Kotlin candidate filter, scorer, and multi-frame voting engine.
 * Enforces `^[A-Za-z0-9]{6,12}$` and resolves partial OCR misreads across frames.
 */
class CardCodeVoteEngine(
    private val windowSize: Int = 7,
    private val requiredVotes: Int = 3
) {
    private val codeRegex = Regex("^[A-Za-z0-9]{6,12}$")

    private val ignoredWords = setOf(
        "internet", "network", "hotspot", "mikrotik", "voucher",
        "username", "password", "android", "connect", "spider",
        "serial", "number", "expired", "minute", "minutes",
        "hours", "mobile", "portal", "captive", "wireless",
        "system", "login", "signin", "welcome", "please"
    )

    private data class FrameVote(
        val code: String,
        val score: Float,
        val confidence: Float
    )

    private val recentFrames = ArrayDeque<FrameVote>()
    private var lowLightStreak = 0
    private var unclearTextStreak = 0

    fun reset() {
        recentFrames.clear()
        lowLightStreak = 0
        unclearTextStreak = 0
    }

    fun processFrame(
        observations: List<CandidateObservation>,
        avgLuma: Double
    ): OcrScanState {
        val isLowLight = avgLuma < 42.0
        if (isLowLight) lowLightStreak++ else lowLightStreak = 0

        val best = selectBestCandidate(observations)

        if (best == null) {
            unclearTextStreak++
            // Decay oldest vote slightly if nothing found
            if (recentFrames.size >= windowSize) {
                recentFrames.removeFirst()
            }
            val topExisting = computeTopVote()
            val message = when {
                lowLightStreak >= 3 -> "الإضاءة ضعيفة — شغّل الفلاش أو أعد المسح مع إضاءة أفضل"
                unclearTextStreak >= 6 -> "أعد المسح مع إضاءة أفضل وثبّت الكاميرا على الرقم"
                topExisting != null -> "جاري التحقق من الرمز..."
                else -> "وجّه الكاميرا نحو رقم الكرت"
            }
            return OcrScanState.Scanning(
                candidateCode = topExisting?.first,
                votes = topExisting?.second ?: 0,
                requiredVotes = requiredVotes,
                isLowLight = lowLightStreak >= 2,
                statusMessage = message
            )
        }

        unclearTextStreak = 0
        if (recentFrames.size >= windowSize) {
            recentFrames.removeFirst()
        }
        recentFrames.addLast(best)

        val voteCounts = mutableMapOf<String, Int>()
        val confidenceSums = mutableMapOf<String, Float>()
        val scoreSums = mutableMapOf<String, Float>()

        for (frame in recentFrames) {
            voteCounts[frame.code] = (voteCounts[frame.code] ?: 0) + 1
            confidenceSums[frame.code] = (confidenceSums[frame.code] ?: 0f) + frame.confidence
            scoreSums[frame.code] = (scoreSums[frame.code] ?: 0f) + frame.score
        }

        val winner = voteCounts.entries.maxWithOrNull(
            compareBy<Map.Entry<String, Int>> { it.value }
                .thenBy { scoreSums[it.key] ?: 0f }
        ) ?: return OcrScanState.Scanning()

        val winCode = winner.key
        val winVotes = winner.value
        val avgConf = (confidenceSums[winCode] ?: 0.8f) / winVotes

        val locked = winVotes >= requiredVotes || (winVotes >= 2 && avgConf >= 0.92f)
        return if (locked) {
            OcrScanState.Locked(
                code = winCode,
                confidence = avgConf.coerceIn(0f, 1f)
            )
        } else {
            OcrScanState.Scanning(
                candidateCode = winCode,
                votes = winVotes,
                requiredVotes = requiredVotes,
                isLowLight = isLowLight,
                statusMessage = "تم رصد رمز محتمل — ثبّت الكاميرا للتأكيد ($winVotes/$requiredVotes)"
            )
        }
    }

    private fun computeTopVote(): Pair<String, Int>? {
        if (recentFrames.isEmpty()) return null
        val counts = recentFrames.groupingBy { it.code }.eachCount()
        val top = counts.maxByOrNull { it.value } ?: return null
        return top.key to top.value
    }

    private fun selectBestCandidate(observations: List<CandidateObservation>): FrameVote? {
        var bestVote: FrameVote? = null
        val validCandidates = observations.mapNotNull { obs ->
            val cleaned = obs.text.trim()
            if (!codeRegex.matches(cleaned)) return@mapNotNull null
            if (cleaned.lowercase() in ignoredWords) return@mapNotNull null
            // Purely alphabetic strings are rare for vouchers and often banner labels
            val hasDigit = cleaned.any { it.isDigit() }
            if (!hasDigit && cleaned.length < 7) return@mapNotNull null

            var score = 0f
            // Length preference: 7..11 chars are standard Yemen MikroTik voucher lengths
            score += cleaned.length * 4f
            // Digit presence is strongly preferred for MikroTik cards
            if (hasDigit) score += 35f
            // Prominent font height inside the frame
            score += (obs.normalizedHeight * 180f).coerceAtMost(60f)
            // Center viewfinder ROI bonus
            if (obs.inCenterRoi) score += 45f
            // ML Kit confidence
            score += (obs.mlKitConfidence * 25f)

            // Yemeni phone numbers (9-10 digits starting with 077/073/071/070/078 or 77/73/71/70/78)
            // often appear as support numbers in smaller text at the bottom of cards.
            val looksLikeYemenPhone =
                (cleaned.length == 9 && cleaned.startsWith("7") && cleaned[1] in setOf('7', '3', '1', '0', '8')) ||
                    (cleaned.length == 10 && cleaned.startsWith("07") && cleaned[2] in setOf('7', '3', '1', '0', '8'))
            if (looksLikeYemenPhone && !obs.inCenterRoi) {
                score -= 40f
            } else if (looksLikeYemenPhone && obs.normalizedHeight < 0.04f) {
                score -= 25f
            }

            FrameVote(
                code = cleaned,
                score = score,
                confidence = obs.mlKitConfidence
            )
        }

        for (candidate in validCandidates) {
            if (bestVote == null || candidate.score > bestVote.score) {
                bestVote = candidate
            }
        }
        return bestVote
    }
}

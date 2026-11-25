package com.example.ocr

import kotlin.math.hypot

sealed interface OcrScanState {
    data class Scanning(
        val candidateCode: String? = null,
        val votes: Int = 0,
        val requiredVotes: Int = 3,
        val isLowLight: Boolean = false,
        val isAnalyzingPhoto: Boolean = false,
        val statusMessage: String = "وجّه الكاميرا نحو رقم الكرت"
    ) : OcrScanState

    data class Locked(
        val code: String,
        val confidence: Float,
        val alternatives: List<String> = emptyList(),
        val sourceLabel: String = "الكاميرا"
    ) : OcrScanState

    data class Error(
        val message: String
    ) : OcrScanState
}

data class CandidateObservation(
    val text: String,
    val normalizedHeight: Float,
    val inCenterRoi: Boolean,
    val mlKitConfidence: Float,
    val lineText: String = text,
    val centerX: Float = 0.5f,
    val centerY: Float = 0.5f,
    val passIndex: Int = 0
)

/**
 * Smart non-guessing candidate filter, context-aware scorer, and consensus voting engine.
 * Designed for Yemeni Wi-Fi / MikroTik cards (8-12 digit numeric & 6-12 char alphanumeric).
 */
class CardCodeVoteEngine(
    private val windowSize: Int = 6,
    private val requiredVotes: Int = 2
) {
    private val codeRegex = Regex("^[A-Za-z0-9]{6,12}$")
    private val ipRegex = Regex("""\b\d{1,3}(?:\.\d{1,3}){3}\b""")
    private val serialLineRegex = Regex("""(?i)\b(?:s/?n|serial|ser)\b[:.\s#-]*[A-Za-z0-9]*""")
    private val serialTokenRegex = Regex("""(?i)^sn\d{3,10}$""")
    private val dateLineRegex = Regex("""(?i)\b\d{1,2}[./-](?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec|\d{1,2})[./-]\d{2,4}\b""")
    private val collapsedDateRegex = Regex("""(?i)^\d{1,2}(?:jan|feb|mar|apr|may|jun|jul|aug|sep|oct|nov|dec)\d{2,4}$""")
    private val domainLineRegex = Regex("""(?i)(?:\bwww\.|\.(?:com|net|org|ye|info|co|io)\b)""")
    private val unitTokenRegex = Regex("""(?i)^\d+(?:mb|gb|kb|tb|hr|hrs|min|mins|day|days|ry|yer)$""")
    private val phoneContextRegex = Regex("""(?i)(?:\+967|00967|تلفون|هاتف|جوال|للتواصل|الادارة|الإدارة|العملاء|tel\b|phone|mob|support)""")
    private val phonePairLineRegex = Regex("""\b0?7[01378]\d{7}\s*[-/|,]\s*0?7[01378]?\d{4,7}\b""")
    private val voucherLabelRegex = Regex("""(?i)(?:رمز|كود|كرت|دخول|الرقم|code|pin|voucher|user|pass|card|login)""")

    private val ignoredWords = setOf(
        "internet", "network", "hotspot", "mikrotik", "voucher",
        "username", "password", "android", "connect", "spider",
        "serial", "number", "expired", "minute", "minutes",
        "hours", "mobile", "portal", "captive", "wireless",
        "system", "login", "signin", "welcome", "please",
        "zainnet", "alkasem", "fadaa", "qamar", "unlimited"
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
        return processMultiPassFrame(
            passes = listOf(observations),
            avgLuma = avgLuma,
            burstCapture = false
        )
    }

    fun processMultiPassFrame(
        passes: List<List<CandidateObservation>>,
        avgLuma: Double,
        burstCapture: Boolean = false
    ): OcrScanState {
        val isLowLight = avgLuma < 42.0
        if (isLowLight) lowLightStreak++ else lowLightStreak = 0

        val rankedAcrossPasses = aggregatePassCandidates(passes)
        val best = rankedAcrossPasses.firstOrNull()

        if (burstCapture) {
            return if (best != null && best.consensusScore >= 45f) {
                OcrScanState.Locked(
                    code = best.code,
                    confidence = best.avgConfidence.coerceIn(0f, 1f),
                    alternatives = rankedAcrossPasses.drop(1).take(2).map { it.code },
                    sourceLabel = "التقاط مباشر"
                )
            } else {
                OcrScanState.Error("لم يتم رصد رقم كرت واضح — قرّب الكاميرا داخل الإطار أو فعّل الفلاش")
            }
        }

        if (best == null) {
            unclearTextStreak++
            if (recentFrames.size >= windowSize) {
                recentFrames.removeFirst()
            }
            val topExisting = computeTopVote()
            val message = when {
                lowLightStreak >= 3 -> "الإضاءة ضعيفة — شغّل الفلاش أو أعد المسح مع إضاءة أفضل"
                unclearTextStreak >= 7 -> "ثبّت الكاميرا واقترب من رقم الكرت داخل الإطار"
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
        val voteConfidence = if (best.passHits >= 2) {
            (best.avgConfidence + 0.06f).coerceAtMost(0.99f)
        } else {
            best.avgConfidence
        }
        val frameVote = FrameVote(
            code = best.code,
            score = best.consensusScore,
            confidence = voteConfidence
        )

        if (recentFrames.size >= windowSize) {
            recentFrames.removeFirst()
        }
        recentFrames.addLast(frameVote)

        val voteCounts = mutableMapOf<String, Int>()
        val confidenceSums = mutableMapOf<String, Float>()
        val scoreSums = mutableMapOf<String, Float>()

        for (frame in recentFrames) {
            voteCounts[frame.code] = (voteCounts[frame.code] ?: 0) + 1
            confidenceSums[frame.code] = (confidenceSums[frame.code] ?: 0f) + frame.confidence
            scoreSums[frame.code] = (scoreSums[frame.code] ?: 0f) + frame.score
        }

        val sortedWinners = voteCounts.entries.sortedWith(
            compareByDescending<Map.Entry<String, Int>> { it.value }
                .thenByDescending { scoreSums[it.key] ?: 0f }
        )

        val winner = sortedWinners.firstOrNull() ?: return OcrScanState.Scanning()
        val winCode = winner.key
        val winVotes = winner.value
        val runnerUpVotes = sortedWinners.getOrNull(1)?.value ?: 0
        val avgConf = (confidenceSums[winCode] ?: 0.8f) / winVotes

        val dualPassInstantAgreement = requiredVotes <= 2 &&
            best.code == winCode &&
            best.passHits >= 2 &&
            best.consensusScore >= 115f &&
            runnerUpVotes == 0

        val strongConsensus = winVotes >= requiredVotes ||
            dualPassInstantAgreement ||
            (winVotes >= 2 && avgConf >= 0.84f && runnerUpVotes <= 1)

        return if (strongConsensus) {
            val alternatives = (rankedAcrossPasses.map { it.code } + sortedWinners.map { it.key })
                .distinct()
                .filter { it != winCode }
                .take(2)
            OcrScanState.Locked(
                code = winCode,
                confidence = avgConf.coerceIn(0f, 1f),
                alternatives = alternatives,
                sourceLabel = "الكاميرا"
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

    fun selectSingleImageCandidate(observations: List<CandidateObservation>): OcrScanState {
        return selectMultiPassImageCandidate(
            passes = listOf(observations),
            sourceLabel = "المعرض"
        )
    }

    fun selectMultiPassImageCandidate(
        passes: List<List<CandidateObservation>>,
        sourceLabel: String = "المعرض"
    ): OcrScanState {
        val ranked = aggregatePassCandidates(passes)
        val best = ranked.firstOrNull()
        return if (best != null && best.consensusScore >= 40f) {
            OcrScanState.Locked(
                code = best.code,
                confidence = best.avgConfidence.coerceIn(0f, 1f),
                alternatives = ranked.drop(1).take(2).map { it.code },
                sourceLabel = sourceLabel
            )
        } else {
            OcrScanState.Error("لم يتم العثور على رقم كرت واضح في الصورة — جرّب صورة أوضح أو أقرب للرقم")
        }
    }

    private data class AggregatedCandidate(
        val code: String,
        val passHits: Int,
        val consensusScore: Float,
        val avgConfidence: Float
    )

    private fun aggregatePassCandidates(
        passes: List<List<CandidateObservation>>
    ): List<AggregatedCandidate> {
        val passWinners = mutableMapOf<String, MutableList<FrameVote>>()

        for (observations in passes) {
            val rankedInPass = rankCandidatesInSinglePass(observations)
            val seenInPass = HashSet<String>()
            for (vote in rankedInPass) {
                if (seenInPass.add(vote.code)) {
                    passWinners.getOrPut(vote.code) { mutableListOf() }.add(vote)
                }
            }
        }

        return passWinners.entries
            .map { (code, hits) ->
                val maxScore = hits.maxOf { it.score }
                val avgScore = hits.map { it.score }.average().toFloat()
                val avgConf = hits.map { it.confidence }.average().toFloat()
                val passBonus = (hits.size - 1) * 28f
                val consensus = (avgScore * 0.6f + maxScore * 0.4f) + passBonus
                AggregatedCandidate(
                    code = code,
                    passHits = hits.size,
                    consensusScore = consensus,
                    avgConfidence = if (hits.size >= 2) (avgConf + 0.05f).coerceAtMost(0.99f) else avgConf
                )
            }
            .filter { it.consensusScore > 20f }
            .sortedByDescending { it.consensusScore }
    }

    private fun rankCandidatesInSinglePass(observations: List<CandidateObservation>): List<FrameVote> {
        if (observations.isEmpty()) return emptyList()

        val validTokens = observations.mapNotNull { obs ->
            val cleaned = cleanToken(obs.text) ?: return@mapNotNull null
            if (!isEligibleToken(cleaned, obs.lineText)) return@mapNotNull null
            obs to cleaned
        }

        if (validTokens.isEmpty()) return emptyList()

        val maxHeight = validTokens.maxOf { (obs, _) -> obs.normalizedHeight }.coerceAtLeast(0.01f)
        val hasNonPhoneCandidate = validTokens.any { (obs, cleaned) ->
            !isYemenPhonePattern(cleaned) && !hasPhoneLineContext(obs.lineText)
        }

        val bestByCode = mutableMapOf<String, FrameVote>()
        for ((obs, cleaned) in validTokens) {
            val score = scoreCandidate(
                obs = obs,
                cleaned = cleaned,
                maxHeightInPass = maxHeight,
                hasNonPhoneAlternative = hasNonPhoneCandidate
            )
            val existing = bestByCode[cleaned]
            if (existing == null || score > existing.score) {
                bestByCode[cleaned] = FrameVote(
                    code = cleaned,
                    score = score,
                    confidence = obs.mlKitConfidence
                )
            }
        }

        return bestByCode.values.sortedByDescending { it.score }
    }

    private fun cleanToken(raw: String): String? {
        val trimmed = raw.trim().trim(
            ':', '.', ',', ';', '-', '_', '|', '/', '\\',
            '[', ']', '(', ')', '{', '}', '"', '\'', '*', '#', ' '
        )
        return if (codeRegex.matches(trimmed)) trimmed else null
    }

    private fun isEligibleToken(cleaned: String, lineText: String): Boolean {
        val lower = cleaned.lowercase()
        if (lower in ignoredWords) return false
        if (!cleaned.any { it.isDigit() }) return false
        if (serialTokenRegex.matches(cleaned)) return false
        if (collapsedDateRegex.matches(cleaned)) return false
        if (unitTokenRegex.matches(cleaned)) return false

        // Reject if all characters are identical (e.g., 00000000, 111111)
        if (cleaned.toSet().size <= 1) return false

        // Reject IP addresses on the line containing this token if the token is just digits from it
        if (ipRegex.containsMatchIn(lineText) && lineText.replace(".", "").contains(cleaned)) {
            return false
        }

        // Reject explicit serial-number lines if the token follows SN / Serial
        if (serialLineRegex.containsMatchIn(lineText)) {
            val afterSn = lineText.replace(Regex("(?i).*\\b(?:s/?n|serial|ser)\\b[:.\\s#-]*"), "")
            if (afterSn.startsWith(cleaned, ignoreCase = true) || cleaned.length <= 6) {
                return false
            }
        }

        // Reject dates on the line when the collapsed line matches the date
        if (dateLineRegex.containsMatchIn(lineText)) {
            val collapsedLine = lineText.replace(Regex("[.\\-/\\s]"), "")
            if (collapsedLine.contains(cleaned, ignoreCase = true)) {
                return false
            }
        }

        // Reject domain/URL tokens
        if (domainLineRegex.containsMatchIn(lineText) && lineText.replace(".", "").contains(cleaned, ignoreCase = true)) {
            return false
        }

        return true
    }

    private fun isYemenPhonePattern(cleaned: String): Boolean {
        if (!cleaned.all { it.isDigit() }) return false
        val secondDigits = charArrayOf('7', '3', '1', '0', '8')
        return (cleaned.length == 9 && cleaned[0] == '7' && cleaned[1] in secondDigits) ||
            (cleaned.length == 10 && cleaned.startsWith("07") && cleaned[2] in secondDigits)
    }

    private fun hasPhoneLineContext(lineText: String): Boolean {
        return phoneContextRegex.containsMatchIn(lineText) ||
            phonePairLineRegex.containsMatchIn(lineText) ||
            Regex("""\b0?7[01378]\d{7}\s*[-/]\s*""").containsMatchIn(lineText)
    }

    private fun scoreCandidate(
        obs: CandidateObservation,
        cleaned: String,
        maxHeightInPass: Float,
        hasNonPhoneAlternative: Boolean
    ): Float {
        var score = 0f

        val allDigits = cleaned.all { it.isDigit() }
        val hasLettersAndDigits = cleaned.any { it.isLetter() } && cleaned.any { it.isDigit() }

        // Length preference tuned for Yemeni Wi-Fi vouchers (8-11 digits or 7-10 alphanumeric)
        score += when (cleaned.length) {
            in 8..11 -> 44f
            7, 12 -> 32f
            else -> 20f
        }

        // Format strength
        if (allDigits && cleaned.length in 8..12) {
            score += 38f
        } else if (hasLettersAndDigits) {
            score += 40f
            // Lowercase 7-10 mixed alphanumeric (like 7gvmv8d5, 6l6gns7w) is a very common voucher style
            if (cleaned.none { it.isUpperCase() } && cleaned.length in 7..10) {
                score += 8f
            }
        } else {
            score += 18f
        }

        // Prominent font height inside the frame + relative tallest bonus
        score += (obs.normalizedHeight * 210f).coerceAtMost(70f)
        val heightRatio = obs.normalizedHeight / maxHeightInPass
        if (heightRatio >= 0.82f) {
            score += 24f
        } else if (heightRatio < 0.55f) {
            score -= 18f
        }

        // Center ROI + smooth center proximity
        if (obs.inCenterRoi) {
            score += 42f
        }
        val distFromCenter = hypot((obs.centerX - 0.5f).toDouble(), (obs.centerY - 0.5f).toDouble()).toFloat()
        score += ((1f - (distFromCenter * 1.4f)).coerceIn(0f, 1f)) * 20f

        // Voucher label context (e.g., "رمز الدخول", "PIN", "Code")
        if (voucherLabelRegex.containsMatchIn(obs.lineText) && !serialLineRegex.containsMatchIn(obs.lineText)) {
            score += 32f
        }

        // ML Kit confidence
        score += (obs.mlKitConfidence * 25f)

        // Yemeni phone number discrimination
        val looksLikePhone = isYemenPhonePattern(cleaned)
        val phoneContext = hasPhoneLineContext(obs.lineText)
        if (looksLikePhone) {
            if (phoneContext) {
                score -= 150f
            } else {
                score -= if (hasNonPhoneAlternative) 65f else 30f
                if (!obs.inCenterRoi) score -= 35f
                if (obs.normalizedHeight < 0.045f || heightRatio < 0.72f) score -= 30f
                if (obs.centerY < 0.25f || obs.centerY > 0.75f) score -= 20f
            }
        } else if (phoneContext) {
            score -= 90f
        }

        return score
    }
}

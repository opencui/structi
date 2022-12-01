package io.opencui.du

import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.CharArraySet
import org.apache.lucene.analysis.cn.smart.SmartChineseAnalyzer
import org.apache.lucene.analysis.en.EnglishAnalyzer
import org.apache.lucene.analysis.standard.StandardAnalyzer
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute
import org.apache.lucene.analysis.tokenattributes.OffsetAttribute
import java.util.*

/**
 * This is the central place to manage all the analyzers.
 */
object LanguageAnalyzer {
    val analyzers = mapOf(
            "en" to EnglishAnalyzer(),
            "zh" to SmartChineseAnalyzer()
    )

    // Assume overlapping is false.
    fun get(lang: String, stop: Boolean = true) : Analyzer? {
        return if (stop) {
            analyzers[lang.lowercase(Locale.getDefault())]
        } else {
            getUnstoppedAnalyzer(lang)
        }
    }

    fun  getUnstoppedAnalyzer(lang: String): Analyzer? {
        return when (lang.lowercase(Locale.getDefault())) {
            "en" -> StandardAnalyzer()
            "zh" -> SmartChineseAnalyzer()
            else -> null
        }
    }

    fun getStopSet(lang:String): CharArraySet? {
        return when (lang.lowercase(Locale.getDefault())) {
            "en" -> EnglishAnalyzer.getDefaultStopSet()
            "zh" -> SmartChineseAnalyzer.getDefaultStopSet()
            else -> null
        }
    }
}

data class BoundToken(val token: String, val start: Int, val end: Int)

fun Analyzer.tokenize(text: String) : List<BoundToken> {
    val tokenStream = this.tokenStream("", text)
    val term = tokenStream.addAttribute(CharTermAttribute::class.java)
    val attr = tokenStream.addAttribute(OffsetAttribute::class.java)
    tokenStream.reset()
    val result = ArrayList<BoundToken>()
    while(tokenStream.incrementToken()) {
        result.add(BoundToken(term.toString(), attr.startOffset(), attr.endOffset()))
    }
    tokenStream.close()
    return result
}
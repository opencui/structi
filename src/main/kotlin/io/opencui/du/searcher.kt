package io.opencui.du

import io.opencui.core.Dispatcher
import org.apache.lucene.document.*
import org.apache.lucene.index.DirectoryReader
import org.apache.lucene.index.IndexWriter
import org.apache.lucene.index.IndexWriterConfig
import org.apache.lucene.index.IndexWriterConfig.OpenMode
import org.apache.lucene.index.Term
import org.apache.lucene.queryparser.classic.QueryParser
import org.apache.lucene.search.BooleanClause
import org.apache.lucene.search.BooleanQuery
import org.apache.lucene.search.IndexSearcher
import org.apache.lucene.search.TermQuery
import org.apache.lucene.store.Directory
import org.apache.lucene.store.MMapDirectory
import org.apache.lucene.store.RAMDirectory
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.io.File
import java.nio.file.Paths
import java.util.*
import java.util.regex.Pattern
import kotlin.collections.ArrayList

/**
 * We assume the training expression will be indexed the code generation phase.
 * And this code is used to do search to reduce the number expression that we need
 * to go through.
 *
 * Expression: "I like to watch a <Moive>"
 * context: Frame that we are in, some expression are weak, and only be triggered where context is right.
 * target: the frame that this expression is attached too, payload
 *
 * if context is default, but target is not null, expression is triggering
 * if context is not null, and target is the same, expression is not trigger, but target is context.
 * if context is not null, and target is not same, we can deal with case for confirmation.
 */

fun Document.toScoredDocument(score: Float) : ScoredDocument {
    val utterance: String = getField(ScoredDocument.UTTERANCE).stringValue()
    var typedExpression: String = getField(ScoredDocument.EXPRESSION).stringValue()
    val ownerFrame: String = getField(ScoredDocument.OWNER).stringValue()
    val contextFrame: String? = getField(ScoredDocument.CONTEXTFRAME)?.stringValue()
    val slotTypes: List<String> = getFields(ScoredDocument.SLOTTYPE).map {it.stringValue()}
    val entailedSlots: List<String> = getFields(ScoredDocument.PARTIALEXPRESSION).map {it.stringValue() }
    val label: String? = if (get(ScoredDocument.LABEL) == null) "" else get(ScoredDocument.LABEL)
    return ScoredDocument(score, utterance, typedExpression, ownerFrame, contextFrame, slotTypes, entailedSlots, label)
}


data class ScoredDocument(
    var score: Float,
    override val utterance: String,
    override var typedExpression: String,
    override val ownerFrame: String,
    override val contextFrame: String?,
    val slotTypes: List<String>,
    val entailedSlots: List<String>,
    override val label: String?,

) : Triggerable, IExemplar {
    override val template: String = utterance
    override var owner : String? = ownerFrame

    override val usedFramesInType = mutableListOf<String>()

    override val slotNames = IExemplar.AngleSlotRegex
        .findAll(template)
        .map { it.value.substring(1, it.value.length - 1) }.toList()

    // whether it is exact match.
    override var exactMatch: Boolean = false

    // The next two are used for potential exect match.
    override var possibleExactMatch: Boolean = false
    override var guessedSlot: DUSlotMeta? = null

    override fun clone(): IExemplar { return this.copy() }

    companion object {
        const val UTTERANCE = "utterance"
        const val OWNER = "owner"
        const val LABEL = "label"
        const val SLOTTYPE = "slotType"
        const val CONTEXT = "context"
        const val CONTEXTFRAME = "context_frame"
        const val CONTEXTSLOT = "context_slot"
        const val EXPRESSION = "expression"
        const val PARTIALEXPRESSION = "partial_application"
        val logger: Logger = LoggerFactory.getLogger(Exemplar::class.java)
    }
}

/**
 * This allows us to separate the index logic from parsing logic.
 */
data class IndexBuilder(val dir: Directory, val lang: String) {
    val analyzer = LanguageAnalyzer.get(lang)
    val iwc = IndexWriterConfig(analyzer).apply{openMode = OpenMode.CREATE}
    val writer = IndexWriter(dir, iwc)

    fun index(doc: Document) {
        writer.addDocument(doc)
    }
    fun close() {
        writer.close()
    }
}

fun Exemplar.toDoc(duMeta: DUMeta) : Document {
    val expr = this
    val doc = Document()
    // Use the trigger based probes so that it works for multilingual.

    val expression = Exemplar.buildTypedExpression(expr.template, expr.ownerFrame, duMeta)

    // Instead of embedding into expression, use StringField.
    val slotTypes = buildSlotTypes(duMeta)
    for (slotType in slotTypes) {
        doc.add(StoredField(ScoredDocument.SLOTTYPE, slotType))
    }
    // "expression" is just for searching
    doc.add(TextField(ScoredDocument.EXPRESSION, expression, Field.Store.YES))
    doc.add(StoredField(ScoredDocument.UTTERANCE, expr.template))


    // We assume that expression will be retrieved based on the context.
    // this assume that there are different values for context:
    // default, active frame, active frame + requested slot.
    Exemplar.logger.info("context: ${buildFrameContext()}, expression: $expression, ${expr.template.lowercase(Locale.getDefault())}")
    doc.add(StringField(ScoredDocument.CONTEXT, buildFrameContext(), Field.Store.YES))

    if (contextFrame != null) {
        Exemplar.logger.info("context slot ${contextSlot}")

        doc.add(StoredField(ScoredDocument.CONTEXTFRAME, contextFrame))
        if (contextSlot != null) {
            doc.add(StoredField(ScoredDocument.CONTEXTSLOT, contextSlot))
        }
    }

    doc.add(StoredField(ScoredDocument.OWNER, expr.ownerFrame))


    // TODO: verify and remove the unused code, when we handle pronouns.
    if (!expr.label.isNullOrEmpty())
        doc.add(StringField(ScoredDocument.LABEL, expr.label, Field.Store.YES))
    return doc
}


/**
 * There three type of expressions:`
 * Slot label expression: We want to go to <destination>
 * Slot type expression: We want to go to <City>
 * slot normalized expression: We want to go to <chu fa di> // for chinese, notice is it is in language dependent form.
 */

data class ExpressionSearcher(val agent: DUMeta) {
    val k: Int = 32
    private val maxFromSame: Int = 4
    private val analyzer = LanguageAnalyzer.get(agent.getLang())
    private val reader: DirectoryReader = DirectoryReader.open(buildIndex(agent))
    private val searcher = IndexSearcher(reader)

    val parser = QueryParser(ScoredDocument.EXPRESSION, analyzer)

    /**
     * We assume each agent has its separate index.
     */
    fun search(rquery: String,
               expectations: DialogExpectations = DialogExpectations(),
               emap: MutableMap<String, MutableList<ValueInfo>>? = null): List<ScoredDocument> {
        if (rquery.isEmpty()) return listOf()

        var searchQuery = QueryParser.escape(rquery)


        logger.info("search with expression: $searchQuery")
        val query = parser.parse("expression:$searchQuery")

        // first build the expectation boolean it should be or query.
        // always add "default" for context filtering.
        val contextQueryBuilder = BooleanQuery.Builder()

        contextQueryBuilder.add(TermQuery(Term(ScoredDocument.CONTEXT, "default")), BooleanClause.Occur.SHOULD)
        if (expectations.activeFrames.isNotEmpty()) {
            for (expectation in expectations.getFrameContext()) {
                contextQueryBuilder.add(
                    TermQuery(Term(ScoredDocument.CONTEXT, expectation)),
                    BooleanClause.Occur.SHOULD
                )
                logger.info("search with context: $expectation")
            }
        }

        val queryBuilder = BooleanQuery.Builder()
        queryBuilder.add(query, BooleanClause.Occur.MUST)
        queryBuilder.add(contextQueryBuilder.build(), BooleanClause.Occur.MUST)

        val results = searcher.search(queryBuilder.build(), k).scoreDocs.toList()

        logger.info("got ${results.size} raw results for ${query}")

        if (results.isEmpty()) return emptyList()

        val res = ArrayList<ScoredDocument>()
        val keyCounts = mutableMapOf<String, Int>()
        val topScore = results[0].score
        var lastScore = topScore
        for (result in results) {
            val doc = reader.document(result.doc).toScoredDocument(result.score / topScore)
            val count = keyCounts.getOrDefault(doc.ownerFrame, 0)
            keyCounts[doc.ownerFrame] = count + 1
            if (keyCounts[doc.ownerFrame]!! <= maxFromSame || doc.score == lastScore) {
                logger.info(doc.toString())
                res.add(doc)
            }
            lastScore = doc.score
        }

        logger.info("got ${res.size} results for ${query}")
        return res
    }

    companion object {
        val logger: Logger = LoggerFactory.getLogger(ExpressionSearcher::class.java)

        @JvmStatic
        fun buildIndex(agent: DUMeta): Directory {
            logger.info("Dispatcher.memeoryBased = ${Dispatcher.memoryBased}")
            // Use ram directory, not as safe, but should be faster as we reduced io.
            return if (Dispatcher.memoryBased) {
                RAMDirectory().apply {
                    buildIndexRaw(agent, this)
                }
            } else {
                val dirAsFile = File("./index/${agent.getOrg()}_${agent.getLabel()}_${agent.getLang()}_${agent.getBranch()}")
                val path = Paths.get(dirAsFile.absolutePath)
                logger.info("Dispatcher.indexing: dirExist = ${dirAsFile.exists()}")
                // Make sure we delete the past index for springboot so that at least we use the newer version
                // as we are rely on org/agent/version for uniqueness, which might fail.
                val needIndex = !dirAsFile.exists()
                MMapDirectory(path).apply{
                     if (needIndex) {
                         buildIndexRaw(agent, this)
                     }
                }
            }
        }

        fun buildIndexRaw(agent: DUMeta, dir: Directory) {
            val expressions = agent.expressionsByFrame.values.flatten()
            logger.info("[ExpressionSearch] build index for ${agent.getLabel()}")
            val indexBuilder = IndexBuilder(dir, agent.getLang())
            expressions.map { indexBuilder.index(it.toDoc(agent)) }
            indexBuilder.close()
        }
    }
}


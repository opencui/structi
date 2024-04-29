package io.opencui.du

import org.apache.lucene.analysis.Analyzer
import org.slf4j.LoggerFactory
import java.time.*
import kotlin.collections.ArrayList
import kotlin.collections.HashMap
import io.opencui.serialization.*
import java.io.Serializable
import java.util.*
import clojure.java.api.Clojure
import clojure.lang.*
import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import java.io.File



/**
 * The normalizer is used to find the all occurrence of entities, and mark it with annotation.
 * Value should be in json format.
 */
class ValueInfo(
    val type: String,
    val start: Int,
    val end: Int,
    val value: Any? = null,
    val recognizer: EntityRecognizer? = null,
    val leaf: Boolean = true,
    var score: Float = 2.0f,
    val partialMatch: Boolean = false,
    val origValue: String? = null,
    val latent: Boolean = true) {
    // This is useful for slot resolutions, when we have multiple slots with the same type.
    val possibleSlots = mutableSetOf<String>()

    var typeSurroundingSupport : Boolean = false
    var covered: Boolean = false

    override fun toString() : String {
        return "$type: $latent: @($start, $end) $typeSurroundingSupport"
    }

    fun norm() : String? {
        return recognizer?.getNormedValue(this)
    }

    fun original(utterance: String): String{
        return utterance.substring(start, end)
    }

    fun withNewType(type: String) : ValueInfo {
        val res = ValueInfo(type, start, end, value, recognizer, latent = latent)
        res.typeSurroundingSupport = typeSurroundingSupport
        return res
    }

}


fun valueInfo(type: String, start: Int, end: Int, value: JsonObject, recognizer: EntityRecognizer?, latent: Boolean = false): ValueInfo {
    return ValueInfo(type, start, end, value, recognizer = recognizer, latent=latent)
}


/**
 * We will have different type of entity recognizer, ducking, list, and maybe others.
 * These will be used by both span detection, global rescoring.
 *
 * Also, each EntityRecognizer will be responsible for a set of entities.
 * All EntityRecognizer are assumed to have a companion object which can
 * be used to build the recognizer for the language.
 *
 * We will assume for each agent, we have a separate recognizer, for the ones that need
 * to be shared, we will add a wrapper.
 *
 * We do not assume the same tokenizer will be used across, so we operate on the char level.
 *
 * To support any entity, we need to wire up the EntityType with corresponding serializer
 * so that the compiler know what do.
 *
 * TODO: need to figure out multi valued cases here.
 */
interface EntityRecognizer {
    /**
     * Takes a user utterance, and current expected types, it emits the recognized entity input
     * entity map, where key is the type of entity, and value is list of spans for that entity type.
     */
    fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<ValueInfo>>)

    /**
     *  Return null if there is no normalization info, or string that can be decoded back to the object
     *  via Json deserialization.
     */
    fun getNormedValue(value: ValueInfo): String?
}


// This adds the regex recognizer
class RegexRecognizer: EntityRecognizer {
    constructor(agent: DUMeta) {
        for (entity in agent.getEntities()) {
            val meta = agent.getEntityMeta(entity) as EntityType
            if (meta.recognizer.contains("RegexRecognizer")) {
                regexes[entity] = meta.pattern!!.toRegex()
            }
        }
    }
    constructor(spatterns: Map<String, String>) {
        for ((key, spattern) in spatterns) {
            regexes[key] = spattern.toRegex()
        }
    }

    val regexes = mutableMapOf<String, Regex>()

    override fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<ValueInfo>>) {
        for ((key, pattern) in regexes) {
            val matches = pattern.findAll(input)
            val spans = mutableListOf<ValueInfo>()
            for (match in matches) {
                spans.add(ValueInfo(
                    key,
                    match.range.start,
                    match.range.endInclusive + 1,
                    value=match.value,
                    recognizer = this))
            }
            emap[key] = spans
        }
    }

    override fun getNormedValue(value: ValueInfo): String? {
        return value.value as String
    }
}


// This version only detect and normalize.
class DucklingRecognizer(val agent: DUMeta):  EntityRecognizer {
    val timezone = agent.getTimezone()
    val lang = agent.getLang()

    val supported = mutableSetOf<String>()

    init {
        val entities = agent.getEntities()
        for (key in entities) {
            val entity = agent.getEntityMeta(key)
            if (entity?.recognizer?.find { it == "DucklingRecognizer" } != null) {
                logger.info("$key does need DucklingRecognizer")
                supported.add(key)
            } else {
                logger.info("$key does not need DucklingRecognizer")
            }
        }
    }

    fun getType(dim: String): List<String> {
        return when (dim) {
            "time" -> listOf("java.time.LocalTime", "java.time.LocalDate", "java.time.YearMonth", "java.time.Year")
            "email" -> listOf("io.opencui.core.Email")
            "phone-number" -> listOf("io.opencui.core.PhoneNumber")
            "number" -> listOf("kotlin.Int", "kotlin.Float")
            "ordinal" -> listOf("io.opencui.core.Ordinal")
            else -> listOf("io.opencui.core.${dim}")
        }
    }

    // TODO: need to change this for different types.
    override fun getNormedValue(value: ValueInfo): String? {
        return when (value.type) {
            "java.time.LocalTime" -> parseLocalTime(value.value)
            "java.time.LocalDate" -> parseLocalDate(value.value)
            "java.time.YearMonth" -> parseYearMonth(value.value)
            "java.time.Year" -> parseYear(value.value)
            "kotlin.Int" -> parseIt(value.value!!)
            "kotlin.Float" -> parseIt(value.value!!)
            else -> "\"${parseIt(value.value!!)}\""
        }
    }

    fun parseLocalTime(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 5, 19, 11)
        return if (strValue != null) Json.encodeToString(LocalTime.parse(strValue)) else null
    }

    fun parseLocalDate(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 4, 10)
        return if (strValue != null) Json.encodeToString(LocalDate.parse(strValue)) else null
    }

    fun parseYearMonth(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 2, 7)
        return if (strValue != null) Json.encodeToString(YearMonth.parse(strValue)) else null
    }

    fun parseYear(value: Any?): String? {
        value as JsonObject
        val strValue = parseTime(value, 0, 4)
        return if (strValue != null) Json.encodeToString(Year.parse(strValue)) else null
    }

    fun parseTime(value: JsonObject, grainIndexTarget: Int, len: Int, start: Int = 0): String? {
        val primitive = value["value"] as JsonPrimitive? ?: return null
        val grain = (value["grain"] as JsonPrimitive).content()
        val grainIndex = getGrainIndex(grain)
        if (grainIndex < 0) return null
        if (grainIndex != grainIndexTarget) return null
        return primitive.content().substring(start, len)
    }

    fun parseIt(value: Any): String? {
        value as JsonObject
        if (!value.containsKey("value")) return null
        return (value["value"] as JsonPrimitive).content()
    }

    override fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<ValueInfo>>) {
        // Because of a bug in duckling, we need to call duckling more than once.
        val elements = parse(input, lang, timezone)
        // Types should be used to activate the latent from true to false, but no need to send to duckegg, as
        // we have stopped to 
        // The first thing, we use the smallest span for the same value (include grain),
        // For now, simply dedup the time related things, based on dim:value:grain:span
        val cleaned = filterRaw(elements, this)
        logger.info(elements.toString())
        // fill the emap
        for (item in cleaned) {
            val types = getType(item.type)
            for (typeFullName in types) {
                val ea = item.withNewType(typeFullName)
                if (ea.norm() == null) continue
                if (!supported.contains(typeFullName)) continue
                if (!emap.containsKey(typeFullName)) {
                    emap[typeFullName] = mutableListOf()
                }
                emap[typeFullName]!!.add(ea)
            }
        }
    }

    companion object {
        val logger = LoggerFactory.getLogger(DucklingRecognizer::class.java)
        val grains: List<String> = listOf("year", "quarter", "month", "week", "day", "hour", "minute", "second")


        fun getGrainIndex(grain: String): Int {
            return grains.indexOf(grain)
        }

        fun getEffectiveTime(value: String, grain: String): String {
            val index = getGrainIndex(grain)
            return if (index <= 4) {
                value.substring(0, 10)
            } else {
                value.substring(11, 19)
            }
        }

        fun getEffectiveTime(valObject: JsonObject): String {
            val value = valObject.getPrimitive("value").content()
            val grain = valObject.getPrimitive("grain").content()
            return getEffectiveTime(value, grain)
        }



        /**
         * dialog state tracker is the standard term where one use to convert natural language
         * user input to frame events that can be used to update dialog state, the main functionality here
         * is to use natural language understanding to convert the unstructured text into structured
         * data, in a domain where business logic is defined.
         *
         * The main design principle is to reduce the learning curve as much as possible for the developers
         * so that they can focus on the business logic side, instead of having to also be a nlu expert.
         *
         * For this, we assume that we get expectation/system utterance from session manager, and then we call
         * dst with expectation and user text.
         * https://dpom.github.io/clj-duckling/DeveloperGuide.html
         * under duckling side
         * lein jar
         * or
         * lein uberjar
         *
         * Build are based on:
         * https://github.com/utsav91092/java-Duckling/blob/master/DucklingJavaWrapper/build.gradle
         */

        // Since we only care about local time.
        fun parse(input: String, lang: String, timezone: String? = null, dims: List<String> = emptyList()): JsonArray {
            // Setup the time zone, so that today is normalized in the right way.
            var context = Clojure.`var`("clojure.core", "hash-map").invoke() as IPersistentMap
            if (timezone != null) {
                val zone = ZoneId.of(timezone)
                val localTime = LocalDateTime.now()
                val zonedTime = localTime.atZone(zone)

                val refTime = Clojure.`var`("duckling.time.obj", "t").invoke(
                    zonedTime.offset.totalSeconds / 3600, zonedTime.year, zonedTime.month.value,
                    zonedTime.dayOfMonth, zonedTime.hour, zonedTime.minute
                )

                context = Clojure.`var`("clojure.core", "hash-map").invoke(
                    Keyword.intern("reference-time"), refTime,
                ) as IPersistentMap
            }

            // This is used to filter the entities
            var list: IPersistentList = Clojure.`var`("clojure.core", "list").invoke() as IPersistentList
            for (dim in dims) {
                list = list.cons(dim) as IPersistentList
            }

            val res = Clojure.`var`("duckling.core", "detect").invoke("$lang\$core", input, list, context) as LazySeq
            return Json.decodeFromString<JsonArray>(
                Clojure.`var`("clojure.data.json", "write-str").invoke(res) as String
            )
        }

        // Test if it is time.
        fun ValueInfo.isTime(): Boolean {
            return type == "time"
        }

        fun ValueInfo.key() : String {
            return if (isTime()) {
                val valObject = value as JsonObject
                val value = valObject.getPrimitive("value").content()
                val grain = valObject.getPrimitive("grain").content()
                "$type:${getEffectiveTime(value, grain)}:${grain}"
            } else {
                type
            }
        }

        fun timeValueSet(v: ValueInfo): Set<String> {
            val valObj = (v.value as JsonObject)
            if (!valObj.containsKey("values")) return emptySet()
            return valObj.getJsonArray("values").map { getEffectiveTime(it as JsonObject) }.toSet()
        }

        fun isTimeAgree(v0: ValueInfo, v1: ValueInfo): Boolean {
            val v0values = timeValueSet(v0)
            val v1values = timeValueSet(v1)
            val v0v1 = v0values.minus(v1values)
            // for now, we assume it is used inside time branch.
            return v0v1.isEmpty()
        }

        // This simplify this,
        fun convertRaw(items: JsonObject, recognizer: EntityRecognizer? = null): ValueInfo {
            val type = (items.get("dim") as JsonPrimitive).content()
            val start = items.getPrimitive("start").content().toInt()
            val end = items.getPrimitive("end").content().toInt()
            val latent = items.getPrimitiveIfExist("latent")?.content().toBoolean() ?: false
            val value = items.get("value") as JsonObject
            return valueInfo(type, start, end, value, recognizer, latent)
        }


        fun filterRaw(elements: JsonArray, recognizer: EntityRecognizer? = null): List<ValueInfo> {
            // arrange item by lens.
            // The first thing, we use the smallest span for the same value (include grain),
            // For now, simply dedup the time related things.
            val lensMap = mutableMapOf<Int, MutableList<ValueInfo>>()
            for (element in elements) {
                val value = convertRaw(element as JsonObject, recognizer)
                val len = value.end - value.start
                if (!lensMap.containsKey(len)) {
                    lensMap[len] = mutableListOf()
                }
                lensMap[len]!!.add(value)
            }

            val typevaluesMap = mutableMapOf<String, MutableList<ValueInfo>>()
            val lens = lensMap.keys.toList().sorted()
            for (len in lens) {
                for (item in lensMap[len]!!) {
                    val valObject = item.value as JsonObject
                    if (item.type == "interval") continue
                    // For now, we assume we only handle single value slot, not the interval like things.
                    if (!valObject.containsKey("value")) {
                        logger.info("Found ${item.type} with no value field.")
                        continue
                    }

                    val key = item.key()
                    if (!typevaluesMap.containsKey(key)) {
                        typevaluesMap[key] = mutableListOf()
                        typevaluesMap[key]!!.add(item)
                    } else {
                        val matched = typevaluesMap[key]!!
                        var covered = false
                        var addLargeSpan = false
                        for (match in matched) {
                            if (item.start <= match.start && item.end >= match.end) {
                                // if item is time, then match is time, time
                                if (item.isTime() && isTimeAgree(match, item)) {
                                    match.typeSurroundingSupport = true
                                    covered = true
                                } else {
                                    addLargeSpan = true
                                    match.covered = true
                                }
                            }
                        }
                        // time we use the small span, others user large span
                        if (!covered || addLargeSpan) {
                            typevaluesMap[key]!!.add(item)
                        }
                    }
                }
            }

            // only have
            return typevaluesMap.values.flatten().filter { it.covered == false }
        }


        @JvmStatic
        fun main(args: Array<String>) {
            ClojureInitializer.init(listOf("en", "zh"), listOf("./core/libs/duckling-0.4.24-standalone.jar"))
            val input = "I will leave at 3 in the morning"

            val parsed = parse(input, "en", "America/New_York")
            for (item in parsed) {
                println(item)
            }
            val cleaned = filterRaw(parsed)
            for (item in cleaned) {
                println(item)
                println(input.substring(item.start, item.end))
            }
        }
    }
}

object ClojureInitializer {
    fun init( langs : List<String>, jars: List<String> = emptyList()) {

        val require = Clojure.`var`("clojure.core", "require")
        require.invoke(Clojure.read("clojure.data.json"));

        val originalClassLoader = Thread.currentThread().contextClassLoader
        if (jars.isNotEmpty()) {
            // Manually add class path for clojure.
            val newClassLoader = DynamicClassLoader(originalClassLoader)
            val urls = jars.map { File(it).absoluteFile.toURI().toURL() }
            println("Original class loader: $originalClassLoader")
            for (url in urls) {
                println("Loading $url")
                newClassLoader.addURL(url)
            }
            Thread.currentThread().contextClassLoader = newClassLoader
        }
        require.invoke(Clojure.read("duckling.core"))

        var langList: ISeq? = null
        for (lang in langs) {
            println(lang)
            println(langList)
            langList = if (langList == null) RT.list(lang) else langList.cons(lang)
        }

        Clojure.`var`("duckling.core", "load!").invoke(langList)

        println("Switching back to original class loader: $originalClassLoader")
        Thread.currentThread().contextClassLoader = originalClassLoader
    }
}


class ListRecognizer(val lang: String) : EntityRecognizer, Serializable {

    data class TypedLabel(val typeId: Int, val labelId: Int, val leaf: Boolean): Serializable
    data class TypedMention(val typeId: Int, val mentionId: Int): Serializable

    val maxNgramSize = 5

    val typeTable = StringIdTable()
    val labelTable = StringIdTable()
    val mentionTable = StringIdTable()
    val mentionIndex = ArrayList<ArrayList<TypedLabel>>()

    val tokenTable = StringIdTable()
    // from token to all the mentions related to it.
    val tokenIndex = ArrayList<ArrayList<TypedMention>>()

    fun updateMentionIndex(mentionId: Int, labelId: Int, typeId: Int, leaf: Boolean) {
        mentionIndex.ensureCapacity(mentionId + 1)
        if (mentionIndex.size <= mentionId) mentionIndex.add(ArrayList())
        mentionIndex[mentionId].add(TypedLabel(typeId, labelId, leaf))
    }

    fun updateTokenIndex(tokenId: Int, mentionId: Int, typeId: Int) {
        tokenIndex.ensureCapacity(tokenId + 1)
        if (tokenIndex.size <= tokenId) tokenIndex.add(ArrayList())
        tokenIndex[tokenId].add(TypedMention(typeId, mentionId))
    }

    @Transient
    val analyzer: Analyzer? = LanguageAnalyzer.getUnstoppedAnalyzer(lang)
    
    // This method is used to handle the extractive frame like DontCare and That
    fun collectExtractiveFrame(owner: JsonObject, type: String, processed: HashMap<String, ArrayList<String>>) {
        val ownerId = owner.getPrimitive(DUMeta.OWNERID).content()
        if (ownerId != type) return
        val expressions = owner[DUMeta.EXPRESSIONS]!! as JsonArray
        for (expression in expressions) {
            expression as JsonObject
            if (!expression.containsKey(DUMeta.CONTEXT)) continue
            val contextObject = expression[DUMeta.CONTEXT] as JsonObject
            val typeId = contextObject.getPrimitive(DUMeta.TYPEID).content()
            if (!processed.containsKey(typeId)) processed[typeId] = ArrayList()
            val utterance = expression.getPrimitive(DUMeta.UTTERANCE).content()
            processed[typeId]?.add(utterance)
        }
    }

    fun getLabel(mentionId: Int, typeId: Int): String? {
        val occurrences = mentionIndex[mentionId]
        val filtered = occurrences.filter { it.typeId == typeId }
        // For now, we return if we can uniquely determine a label.
        return if (filtered.size == 1) {
            labelTable.getString(filtered[0].labelId)
        } else {
            null
        }
    }

    // After we collect all the phrases related we add this for recognizer.
    fun memorizeExtractiveFrame(label: String, type:String, processed: HashMap<String, ArrayList<String>>) {
        val phrases = processed[type] ?: return
        val typeId = typeTable.put(type)
        if (phrases.size > 0) {
            val labelId = labelTable.put(label)
            for (phrase in phrases) {
                val mentionId = mentionTable.put(phrase)
                updateMentionIndex(mentionId, labelId, typeId, true)
            }
        }
    }

    // TODO(sean) we need to make sure input is simple space separated for en.
    override fun parse(input: String, types: List<String>, emap: MutableMap<String, MutableList<ValueInfo>>) {
        val spanlist = analyzer!!.tokenize(input)
        // We should try the longest match.
        val typedSpans = HashMap<Int, MutableList<Pair<Int, Int>>>()
        val partialMatch = mutableListOf<ValueInfo>()
        for (i in 0..spanlist.size) {
            for (k in 0..maxNgramSize) {
                if ( i + k >= spanlist.size) continue
                val range = IntRange(spanlist[i].start, spanlist[i+k].end - 1)
                val key = input.slice(range)

                val mentionId = mentionTable.getId(key)
                if (mentionId != null) {
                    val occurrences = mentionIndex[mentionId]
                    for (occurrence in occurrences) {
                        val typeId = occurrence.typeId
                        val labelId = occurrence.labelId
                        val leaf = occurrence.leaf
                        val type = typeTable.getString(typeId)
                        val label = labelTable.getString(labelId)

                        if (!emap.containsKey(type)) {
                            emap[type] = mutableListOf()
                        }

                        if (!typedSpans.containsKey(typeId)) {
                           typedSpans[typeId] = mutableListOf()
                        }

                        typedSpans[typeId]!!.add(Pair(spanlist[i].start, spanlist[i + k].end))
                        emap[type]!!.add(ValueInfo(type, spanlist[i].start, spanlist[i + k].end, label, this, leaf))
                    }
                }  else {
                    // when this is not mention match
                    val tokenId = tokenTable.getId(key)
                    if (tokenId != null) {
                        val occurrences = tokenIndex[tokenId]
                        for (occurrence in occurrences) {
                            val typeId = occurrence.typeId
                            val mentionId = occurrence.mentionId
                            val type = typeTable.getString(typeId)
                            partialMatch.add(
                                ValueInfo(
                                    type,
                                    spanlist[i].start,
                                    spanlist[i + k].end,
                                    getLabel(mentionId, typeId),
                                    this,
                                    true,
                                    partialMatch = true,
                                    origValue = input.substring(spanlist[i].start, spanlist[i + k].end)
                                )
                            )
                        }
                    }
                }
            }
        }

        for (span in partialMatch) {
            val target = Pair(span.start, span.end)
            val typeId = typeTable.getId(span.type)
            val listOfFullMatchedSpan = typedSpans[typeId]
            if (!covered(target, listOfFullMatchedSpan)) {
                logger.debug("Covered $target is not covered by $listOfFullMatchedSpan with $span" )
                if (!emap.containsKey(span.type)) {
                    emap[span.type] = mutableListOf()
                }
                emap[span.type]!!.add(span)
            }
        }
        logger.debug(emap.toString())
    }

    private fun covered(target: Pair<Int, Int>, ranges: List<Pair<Int, Int>>?): Boolean {
        if (ranges.isNullOrEmpty())
            return false

        for (range in ranges) {
            if ((target.first >= range.first && target.second <= range.second)) {
                return true
            }
        }
        return false
    }

    fun findRelatedEntity(type: String, token: String): List<String>? {
        val typeId = typeTable.getId(type)
        val tokenId = tokenTable.getId(token) ?: return null
        return tokenIndex[tokenId].filter { it.typeId == typeId }.map { mentionTable.getString(it.mentionId) }
    }


    override fun getNormedValue(value: ValueInfo): String? {
        // TODO: should we make sure that label of entity instance has no space?
        return when (value.type) {
            "kotlin.Boolean" -> value.value as String?
            "java.util.TimeZone" -> Json.encodeToString(TimeZone.getTimeZone(value.value as String))
            "java.time.ZoneId" -> Json.encodeToString(ZoneId.of(value.value as String))
            else -> "\"${value.value}\""
        }
    }

    companion object {
        const val PARTIALMATCH = "_partial_match"

        fun isPartialMatch(norm: String?) : Boolean {
            return norm == "\"_partial_match\""
        }

        val logger = LoggerFactory.getLogger(ListRecognizer::class.java)
    }
}


object ListRecognizerBuilder {
    val processedDontcare = HashMap<String, ArrayList<String>>()
    val processedThat = HashMap<String, ArrayList<String>>()
    val fullMatches = HashMap<Pair<String, String>, MutableSet<String>>()
    val partialIndex = HashMap<String, MutableList<String>>()

    fun add(listRecognizer: ListRecognizer,
            typeId:Int,
            type: String,
            entryLabel: String,
            expressions: List<String>,
            leaf: Boolean) {
        // TODO(sean): again, the norm need to be language related.
        val labelId = listRecognizer.labelTable.put(entryLabel)
        for (mention in expressions) {
            val key = mention.lowercase().trim{ it.isWhitespace() }
            val mentionId = listRecognizer.mentionTable.put(key)
            // Handle full match.
            listRecognizer.updateMentionIndex(mentionId, labelId, typeId, leaf)

            // Handle partial match.
            val tokens = listRecognizer.analyzer!!.tokenize(key).map{it.token}
            for (token in tokens) {
                val tokenId = listRecognizer.tokenTable.put(token)
                listRecognizer.updateTokenIndex(tokenId, mentionId, typeId)
                if (!partialIndex.containsKey(token)) partialIndex[token] = mutableListOf()
                if (!fullMatches.containsKey(Pair(token, type))) {
                    fullMatches[Pair(token, type)] = mutableSetOf()
                }
                fullMatches[Pair(token,type)]!!.add(entryLabel)
                partialIndex[token]!!.add(entryLabel)
            }
        }
    }

    // This method is used to handle the extractive frame like DontCare and That
    fun build(agent: ExtractiveMeta, listRecognizer: ListRecognizer) {
        logger.info("Init ListRecognizer...")
        // We can have two kind of DontCare, proactive, and reactive. The DontCare on the entity
        // is also proactive, meaning user can say it directly. instead of just replying prompt.

        // Populate the typeId
        agent.getEntities().map { listRecognizer.typeTable.put(it) }

        // Handle every type here.
        for (type in agent.getEntities()) {
            val typeId = listRecognizer.typeTable.getId(type)!!

            // TODO(sean): we should get the name instead of using label here.
            // now we assume that we have normed token.
            // TODO(sean): assume normed can not be recognized unless it is also listed in the rest.

            // for internal node
            val meta = agent.getEntityMeta(type)
            val children = meta?.children ?: emptyList()
            for (child in children) {
                // TODO(sean): Use * to mark the internal node, need to ake sure that is pattern is stable
                val entryLabel = "$child"
                val expressions = agent.getTriggers(child)
                add(listRecognizer, typeId, type, entryLabel, expressions,false)
            }

            // Actual instances.
            // TODO (sean): find a better place to hard code.
            val content = if (type != "io.opencui.core.SlotType") agent.getEntityInstances(type) else agent.getSlotTriggers()
            logger.info("process entity type $type with ${content.size} entries.")
            for ((entryLabel, expressions) in content) {
                add(listRecognizer, typeId, type, entryLabel, expressions, true)
            }

            // process entity dontcare annotations
            if (processedDontcare.containsKey(type)) {
                listRecognizer.memorizeExtractiveFrame(IStateTracker.DontCareLabel, IStateTracker.FullDontCare, processedDontcare)
            }

            // Let's handle the pronoun that here. Notice currently that is only via extractive understanding.
            if (processedThat.containsKey(type)) {
                listRecognizer.memorizeExtractiveFrame(IStateTracker.ThatLabel, IStateTracker.FullThat, processedThat)
            }
        }
    }
    
    fun build(entities: Map<String, Map<String, List<String>>>, listRecognizer: ListRecognizer) {
        logger.info("Init ListRecognizer...")
        // We can have two kind of DontCare, proactive, and reactive. The DontCare on the entity
        // is also proactive, meaning user can say it directly. instead of just replying prompt.

        // Populate the typeId
        entities.keys.map { listRecognizer.typeTable.put(it) }

        // Handle every type here.
        for (type in entities.keys ) {
            val typeId = listRecognizer.typeTable.getId(type)!!

            // for internal node
            val children = entities[type]!!
            logger.info("process entity type $type with ${children.size} entries.")
            for (entryLabel in children.keys) {
                // TODO(sean): Use * to mark the internal node, need to ake sure that is pattern is stable
                val expressions = children[entryLabel]!!
                logger.info("process entity type $entryLabel with ${expressions.size} entries.")
                add(listRecognizer, typeId, type, entryLabel, expressions,true)
            }
        }
    }

    operator fun invoke(agent: DUMeta) : ListRecognizer {
        val listRecognizer = ListRecognizer(agent.getLang())
        build(agent, listRecognizer)
        return listRecognizer
    }

    operator fun invoke(lang: String, entities: Map<String, Map<String, List<String>>>) : ListRecognizer{
        val listRecognizer = ListRecognizer(lang)
        build(entities, listRecognizer)
        return listRecognizer
    }

    val logger = LoggerFactory.getLogger(ListRecognizer::class.java)
}

fun defaultRecognizers(agent: DUMeta) : List<EntityRecognizer> {
    return listOf(
        ListRecognizerBuilder(agent),
        DucklingRecognizer(agent)
    )
}

fun List<EntityRecognizer>.recognizeAll(utterance:String, types: List<String>, emap: MutableMap<String, MutableList<ValueInfo>>) {
    forEach { it.parse(utterance, types, emap) }
}

// Simply provide a way to convert string to id, or id to string back.
class StringIdTable : Serializable {
    val stringToId = HashMap<String, Int>()
    val idToString = ArrayList<String>()
    fun put(key: String) : Int {
        if (!stringToId.containsKey(key)) {
            stringToId[key] = idToString.size
            idToString.add(key)
        }
        return stringToId[key]!!
    }

    fun getString(index: Int) : String {
        return idToString[index]
    }

    fun getId(key: String): Int? = stringToId[key]
}

package io.opencui.test

import io.opencui.core.*
import io.opencui.core.da.DialogActRewriter
import io.opencui.du.*
import io.opencui.du.DUMeta.Companion.toLowerProperly
import io.opencui.serialization.*
import kotlin.reflect.KClass


abstract class JsonDUMeta() : DUMeta {
    abstract val entityMetas: Map<String, EntityMeta>
    abstract val slotMetaMap: Map<String, List<DUSlotMeta>>
    abstract val aliasMap: Map<String, List<String>>
    val subtypes: MutableMap<String, List<String>> = mutableMapOf()

    override fun getSubFrames(fullyQualifiedType: String): List<String> {
        return subtypes[fullyQualifiedType] ?: emptyList()
    }

    override fun getEntities(): Set<String> {
        return entityMetas.keys
    }

    override fun getTriggers(name: String): List<String> {
        return aliasMap[name] ?: listOf()
    }

    override fun getEntityMeta(name: String): IEntityMeta? {
        return entityMetas[name]
    }

    override fun getSlotMetas(frame: String): List<DUSlotMeta> {
        return slotMetaMap[frame] ?: listOf()
    }

    override fun isEntity(name: String): Boolean {
        return entityMetas.containsKey(name)
    }

    override fun getSlotTriggers(): Map<String, List<String>> {
        val results = mutableMapOf<String, List<String>>()
        for ((frame, metas) in slotMetaMap) {
            for (slotMeta in metas) {
                results["${frame}.${slotMeta.label}"] = slotMeta.triggers
            }
        }
        return results
    }

    companion object {
        private fun parseContext(context: JsonObject?) : ExpressionContext? {
            if (context == null) return null
            val frame = getContent(context["frame_id"])!!
            val slot = getContent(context["slot_id"])
            return ExpressionContext(frame, slot)
        }

        private fun getContent(primitive: JsonElement?): String? {
            return (primitive as JsonPrimitive?)?.content()
        }

        /**
         * This parses expression json file content into list of expressions, so that we
         * can index them one by one.
         */
        @JvmStatic
        fun parseExpressions(exprOwners: JsonArray, bot: DUMeta): Map<String, List<Exemplar>> {
            val resmap = mutableMapOf<String, List<Exemplar>>()
            for (owner in exprOwners) {
                owner as JsonObject
                val ownerId = getContent(owner["owner_id"])!!
                if (!resmap.containsKey(ownerId)) {
                    resmap[ownerId] = ArrayList<Exemplar>()
                }
                val res = resmap[ownerId] as ArrayList<Exemplar>
                val expressions = owner["expressions"] ?: continue
                expressions as JsonArray
                for (expression in expressions) {
                    val exprObject = expression as JsonObject
                    val contextObject = exprObject["context"] as JsonObject?
                    val context = parseContext(contextObject)
                    val utterance = getContent(exprObject["utterance"])!!
                    val label = if (exprObject.containsKey("label")) getContent(exprObject["label"])!! else ""
                    res.add(Exemplar(ownerId, context, label, toLowerProperly(utterance), bot))
                }
                res.apply { trimToSize() }
            }
            return resmap
        }
        fun parseByFrame(agentJsonExpressions: String): JsonArray {
            val root = Json.parseToJsonElement(agentJsonExpressions) as JsonObject
            return root["expressions"]!! as JsonArray
        }
    }

}

data class Agent(val user: String) : IChatbot() {
    constructor() : this("")
    override val duMeta: DUMeta
        get() = Agent.duMeta
    
    val pkgName = this.javaClass.packageName.replace(".", "/")
    override val stateTracker = Agent.stateTracker

    override val rewriteRules: MutableList<KClass<out DialogActRewriter>> = mutableListOf()

    override val routing: Map<String, RoutingInfo> = mapOf(
        "Team B" to RoutingInfo("48", listOf("Intent_No2", "Intent_No3")),
        "Default" to RoutingInfo("47", listOf()))

    init {
        rewriteRules += Class.forName("io.opencui.test.SlotOfferSepInformConfirmRule").kotlin as KClass<out DialogActRewriter>

        extensions[IMobileService::class] = buildManager<IMobileService> {
            builder("default", MobileServiceTemplateImpl.Companion) {
                put("user", "")
                put("password", "")
                put("conn", "io.opencui.provider.SqlConnection")
                put("url", "jdbc:h2:./h2db/mobile;DATABASE_TO_UPPER=FALSE")
            }
        }

        extensions[IVacationService::class] = buildManager<IVacationService>{
            builder("default", VacationServiceTemplateImpl.Companion) {
                put("user", "")
                put("password", "")
                put("conn", "io.opencui.provider.SqlConnection")
                put("url", "jdbc:h2:./h2db/vacation;DATABASE_TO_UPPER=FALSE")
            }
        }

        extensions[IIntentSuggestionService::class] = buildManager<IIntentSuggestionService> {
            builder("default", IntentSuggestionServiceTemplateImpl.Companion) {
                put("user", "")
                put("password", "")
                put("conn", "io.opencui.provider.SqlConnection")
                put("url", "jdbc:h2:./h2db/intents;DATABASE_TO_UPPER=FALSE")
            }
        }

        extensions[IDishService::class] = buildManager<IDishService> {
            builder("default", DishServiceTemplateImpl.Companion) {
                put("user", "")
                put("password", "")
                put("conn", "io.opencui.provider.SqlConnection")
                put("url", "jdbc:h2:./h2db/dish;DATABASE_TO_UPPER=FALSE")
            }
        }

        extensions[IComponent_0915::class] = buildManager<IComponent_0915> {
          builder("default", HelloWorldProvider.Companion) {
            put("name", "world")
            put("label", "T1012")
          }
        }
    }


    companion object {
        val duMeta = loadDUMeta(javaClass.classLoader, "io.opencui", "test", "en", "master", "test")
        val stateTracker = BertStateTracker(duMeta)


        fun loadDUMeta(classLoader: ClassLoader, org: String, agent: String, lang: String, branch: String, version: String, timezone: String = "america/los_angeles"): DUMeta {
            return object : JsonDUMeta() {
                override val entityMetas = Json.decodeFromString<Map<String, EntityMeta>>(
                    classLoader.getResourceAsStream(EntityMetaPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val agentEntities = Json.decodeFromString<Map<String, String>>(
                    classLoader.getResourceAsStream(EntityPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
                override val slotMetaMap = Json.decodeFromString<Map<String, List<DUSlotMeta>>>(
                    classLoader.getResourceAsStream(SlotMetaPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
                override val aliasMap = Json.decodeFromString<Map<String, List<String>>>(
                    classLoader.getResourceAsStream(AliasPath).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val entityContentMap: MutableMap<String, Map<String, List<String>>> = mutableMapOf()

                init {
                    for (entity in agentEntities.entries) {
                        entityContentMap[entity.key] = parseEntityToMapByNT(entity.key, entity.value)
                    }
                }

                override fun getSubFrames(fullyQualifiedType: String): List<String> { return subtypes[fullyQualifiedType] ?: emptyList() }

                override fun getOrg(): String = org
                override fun getLang(): String = lang
                override fun getLabel(): String = agent
                override fun getVersion(): String = version
                override fun getBranch(): String = branch
                override fun getTimezone(): String = timezone

                override fun getEntityInstances(name: String): Map<String, List<String>> {
                    return entityContentMap[name] ?: mapOf()
                }

                override val expressionsByFrame: Map<String, List<Exemplar>>
                    get() = parseExpressions(
                        parseByFrame(classLoader.getResourceAsStream(ExpressionPath).bufferedReader(Charsets.UTF_8).use { it.readText() }), this)
            }
        }
    }
}

data class HelloWorldProvider(
  val config: Configuration,
  override var session: UserSession? = null): IComponent_0915, IProvider {

  override fun testFunction(str: String): String? {
    return "hello ${config["name"]}, $str"
  }

  companion object: ExtensionBuilder<IComponent_0915> {
    override fun invoke(config: Configuration): IComponent_0915 {
      return HelloWorldProvider(config)
    }
  }
}
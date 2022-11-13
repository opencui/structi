package io.opencui.test

import io.opencui.core.*
import io.opencui.core.da.DialogActRewriter
import io.opencui.du.BertStateTracker
import io.opencui.du.DUMeta
import kotlin.reflect.KClass

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
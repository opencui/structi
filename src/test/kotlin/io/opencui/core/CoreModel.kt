package io.opencui.core

import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonValue
import io.opencui.core.da.DialogAct
import io.opencui.core.da.SlotOffer
import io.opencui.core.da.SlotRequest
import io.opencui.core.da.UserDefinedInform
import io.opencui.serialization.Json
import kotlin.reflect.KClass
import kotlin.reflect.KMutableProperty0
import kotlin.reflect.full.isSubclassOf

data class IDonotGetIt(override var session: UserSession? = null) : IIntent {
    override fun searchResponse(): Action? = when {
        else -> UserDefinedInform(this, templateOf("""I did not get that."""))
    }

    override fun createBuilder(): FillBuilder = object : FillBuilder {
        var frame: IDonotGetIt? = this@IDonotGetIt

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}

data class IntentSuggestion(override var session: UserSession? = null
) : IIntent {
    var intentPackage: String? = null
    var intentName: String? = null

    @JsonIgnore
    fun getSuggestions(): List<IntentSuggestion> {
        return listOf(
            IntentSuggestion().apply {
                intentPackage = "io.opencui.test"
                intentName = "BookHotel"
            },
            IntentSuggestion().apply {
                intentPackage = "io.opencui.core"
                intentName = "IDonotKnowWhatToDo"
            }
        )
    }

    @JsonIgnore
    var recommendation: PagedSelectable<IntentSuggestion> = PagedSelectable(
        session, {getSuggestions()}, { IntentSuggestion::class },
        {offers ->
            SlotOffer(offers, "this", "io.opencui.core.IntentSuggestion",
                templateOf(
                    """We have following ${offers.size} choices for intents : ${
                        offers.joinToString(", ") {
                            "(${it.intentPackage}, ${it.intentName})"
                        }
                    }."""
                )
            )
        },
        target = this, slot = "")

    override fun annotations(path: String): List<Annotation> = when(path) {
        "intentPackage" -> listOf(
            SlotPromptAnnotation(
                listOf(
                    SlotRequest(
                        "intentPackage",
                        "kotlin.String",
                        templateOf("Which package?")
                    )
                )
            ),
            ValueRecAnnotation({ recommendation }, false)
        )
        "intentName" -> listOf(
            SlotPromptAnnotation(
                listOf(
                    SlotRequest(
                        "intentName",
                        "kotlin.String",
                        templateOf("Which intent?")
                    )
                )
            )
        )
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame:IntentSuggestion? = this@IntentSuggestion

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            with(filler) {
                addWithPath(EntityFiller({target.get()!!::intentPackage}) { s -> Json.decodeFromString(s) })
                addWithPath(EntityFiller({target.get()!!::intentName}) { s -> Json.decodeFromString(s) })
            }
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            // TODO(xiaobo): why we are triggering this intent action?
            else -> IntentAction(JsonFrameBuilder("{\"@class\": \"${this.intentPackage ?: ""}.${this.intentName ?: ""}\"}", listOf(session)))
        }
    }
}

data class IDonotKnowWhatToDo(override var session: UserSession? = null
) : IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: IDonotKnowWhatToDo?= this@IDonotKnowWhatToDo
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> UserDefinedInform(this, templateOf("""I do not know what to do now."""))
        }
    }
}

data class IntentName(@get:JsonIgnore override var value: String): IEntity {
    override var origValue: String? = null
    @JsonValue
    override fun toString() : String = value
}

data class AbortIntent(override var session: UserSession? = null): AbstractAbortIntent(session) {
    override val builder: (String) -> IEntity? = { Json.decodeFromString<IntentName>(it)}
    override val defaultFailPrompt: (() -> DialogAct)? = { UserDefinedInform(this, templateOf("""Failed to abort!""")) }
    override val defaultSuccessPrompt: (() -> DialogAct)? = {
        UserDefinedInform(
            this,
            templateOf(with(session!!.rgLang) { """${intent?.typeExpression()} is Aborted successfully!""" })
        ) }
    override val defaultFallbackPrompt: (() -> DialogAct)? = {
        UserDefinedInform(
            this,
            templateOf("""Aborted ancestor intent""")
        ) }
}

data class ValueClarification<T: Any>(
    override var session: UserSession? = null,
    override val getClass: () -> KClass<T>,
    override val source: MutableList<T>,
    override var targetFrame: IFrame,
    override var slot: String): AbstractValueClarification<T>(session, getClass, source, targetFrame, slot) {

    @JsonIgnore
    override var target: T? = if (getClass().isSubclassOf(IFrame::class)) getClass().constructors.first().call(session) else null

    @JsonIgnore
    override fun _rec_target(it: T?): PagedSelectable<T> = PagedSelectable(
        session,  {source}, getClass,
        {offers ->
            SlotOffer(offers, "target", getClass().qualifiedName!!,
                templateOf(with(session!!.rgLang) {
                    """by ${targetSlotAlias()}, which do you mean: ${
                        offers.joinToString(", ") {
                            "(${it.expression()})"
                        }
                    }."""
                })
            )
        },
        pageSize = 5, target = this, slot = "target")

    override fun annotations(path: String): List<Annotation> = when(path) {
        "target" -> listOf(
            SlotPromptAnnotation(
                listOf(
                    SlotRequest("target", getClass().qualifiedName!!, templateOf("target?"))
                )
            ),
            TypedValueRecAnnotation<T>({ _rec_target(this) })
        )
        else -> listOf()
    }
}

data class ResumeIntent(override var session: UserSession? = null
) : IIntent {
    var intent: IIntent? = null
    override fun annotations(path: String): List<Annotation> = when(path) {
        "intent" -> listOf(NeverAsk())
        else -> listOf()
    }

    override fun createBuilder() = object : FillBuilder {
        var frame: ResumeIntent?= this@ResumeIntent
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            filler.addWithPath(InterfaceFiller({ frame!!::intent }, createFrameGenerator(frame!!.session!!, "io.opencui.core.IIntent")))
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> UserDefinedInform(
                this,
                templateOf(with(session!!.rgLang) { "We are in the middle of ${intent?.typeExpression()} already, let's continue with the current process." })
            )
        }
    }
}

// hardcode for clean session
data class CleanSession(override var session: UserSession? = null) : IIntent {
    override fun createBuilder() = object : FillBuilder {
        var frame: CleanSession? = this@CleanSession
        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }

    override fun searchResponse(): Action? {
        return when {
            else -> CloseSession()
        }
    }
}

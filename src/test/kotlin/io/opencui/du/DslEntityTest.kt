package io.opencui.du

import io.opencui.core.IChatbot
import io.opencui.core.IChatbot.Companion.loadDUMetaDsl
import io.opencui.serialization.Json
import io.opencui.serialization.JsonArray
import org.apache.lucene.document.Document
import org.apache.lucene.document.Field
import org.apache.lucene.document.StringField
import kotlin.test.assertEquals

import org.junit.Test


class DslEntityTest() : DuTestHelper() {

    object En : LangPack {
        override val frames = listOf(
            frame("Banks_1.TransferMoney") {
                utterance("${'$'}date_time_slot${'$'}")
                utterance("Yes, please make a transfer.")
                utterance("Okay, please make a transfer for me.")
                utterance("Please help me make a money transfer")
                utterance("Okay thats cool please make a fund transfer")
                utterance("Make a transfer of ${'$'}amount${'$'}.") {
                    context("Banks_1.TransferMoney")
                }
                utterance("Great, let's make a transfer.")
                utterance("Make a transfer to ${'$'}recipient_account_name${'$'}")
                utterance("I wanna make a transfer")
                utterance("send ${'$'}amount${'$'} and give it to ${'$'}recipient_account_name${'$'} and go with the ${'$'}account_type${'$'} account") {
                    context("Banks_1.TransferMoney", "amount")
                    label("negation")

                }
                utterance("I'm interested in making a money transfer.")
            },
            frame("io.opencui.core.DontCare") {
                utterance("any recipient") {
                    context("account")
                }
                utterance("whatever frame")
            }
        )

        override val entityTypes: Map<String, EntityType> = mapOf(
            "city" to entityType("city") {
                recognizer("ListRecognizer")
                entity("beijing", "bei jing", "shou du")
                entity("label", "expr1", "expr2")
            },

            "me.test.abstractEntity_1007.Dish" to entityType("me.test.abstractEntity_1007.Dish") {
                children(listOf("me.test.abstractEntity_1007.ChickenDish",
                    "me.test.abstractEntity_1007.HouseSpecial", ))
                recognizer("ListRecognizer")
            },
            "me.test.abstractEntity_1007.ChickenDish" to entityType("me.test.abstractEntity_1007.ChickenDish") {
                parent("me.test.abstractEntity_1007.Dish")
                children(listOf())
                recognizer("ListRecognizer")
                entity("ChickenwithBroccoli","Chicken with Broccoli")
                entity("SweetandSourChicken","Sweet and Sour Chicken")
                entity("ChickenWings","Chicken Wings")
            },
            "me.test.abstractEntity_1007.HouseSpecial" to entityType("me.test.abstractEntity_1007.HouseSpecial") {
                parent("me.test.abstractEntity_1007.Dish")
                children(listOf())
                recognizer("ListRecognizer")
                entity("SesameChicken","Sesame Chicken")
                entity("SpecialMincedChicken","Special Minced Chicken")
                entity("HunanSpecialHalfandHalf","Hunan Special Half and Half")
            }
        )

        override val frameSlotMetas: Map<String, List<DUSlotMeta>> = mapOf()
        override val typeAlias: Map<String, List<String>> = mapOf(
            "io.opencui.core.Email" to listOf("Email"),
            "io.opencui.core.Currency" to listOf("Currency"),
            "io.opencui.core.FrameType" to listOf("Intent Name"),
            "io.opencui.core.EntityType" to listOf("Entity Name"),
            "io.opencui.core.SlotType" to listOf("Slot Name"),
            "io.opencui.core.PromptMode" to listOf("Prompt Mode"),
            "io.opencui.core.Language" to listOf("language"),
            "io.opencui.core.Country" to listOf("country"),
            "io.opencui.core.FillState" to listOf("FillState"),
            "me.test.abstractEntity_1007.Dish" to listOf("dish"),
            "me.test.abstractEntity_1007.ChickenDish" to listOf("Chicken Dish"),
            "me.test.abstractEntity_1007.HouseSpecial" to listOf("House Special"),
            "me.test.abstractEntity_1007.Greeting" to listOf("Greeting"),
            "me.test.abstractEntity_1007.Goodbye" to listOf("Goodbye"),
            "io.opencui.core.IDonotGetIt" to listOf("I don't get it"),
            "io.opencui.core.IDonotKnowWhatToDo" to listOf("I don't know what to do"),
            "io.opencui.core.AbortIntent" to listOf("Abort Intent"),
            "io.opencui.core.GetLiveAgent" to listOf("Hand off"),
            "io.opencui.core.ResumeIntent" to listOf("ResumeIntent"),
            "me.test.abstractEntity_1007.FoodOrdering" to listOf("food ordering"),
            "io.opencui.core.DontCare" to listOf("DontCare"),
            "io.opencui.core.AmountOfMoney" to listOf("AmountOfMoney"),
            "io.opencui.core.Companion" to listOf("Companion"),
            "io.opencui.core.companion.Not" to listOf("companion.Not"),
            "io.opencui.core.companion.Or" to listOf("companion.Or"),
            "io.opencui.core.NextPage" to listOf("next page"),
            "io.opencui.core.PreviousPage" to listOf("previous page"),
          )

        fun Int.getDialogAct(vararg slot: String): String {
            return "En"
        }
    }

    val duMeta: DUMeta = loadDUMetaDsl(En, DslEntityTest::class.java.classLoader, "me.test",
        "abstractEntity_1007", "en", "746395988637257728", "271", "Asia/Shanghai")


    private val normalizers = listOf(ListRecognizer(duMeta))

    @Test
    fun testEntityValue0() {
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        normalizers.recognizeAll("order chicken wings", listOf(), emap)
        assertEquals(emap.size, 1)
    }

    @Test
    fun testEntityValue1() {
        val emap = mutableMapOf<String, MutableList<SpanInfo>>()
        normalizers.recognizeAll("order house special", listOf(), emap)
        assertEquals(emap.size, 1)
        val value = emap["me.test.abstractEntity_1007.Dish"]!![0]
        assert(!value.leaf)
    }
}


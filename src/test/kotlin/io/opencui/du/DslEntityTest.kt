package io.opencui.du

import io.opencui.core.IChatbot.Companion.loadDUMetaDsl
import kotlin.test.assertEquals

import org.junit.Test


class DslEntityTest() : DuTestHelper() {

    object En : LangPack {
        override val frames = listOf(
            frame("Banks_1.TransferMoney") {
                utterance("<date_time_slot>")
                utterance("Yes, please make a transfer.")
                utterance("Okay, please make a transfer for me.")
                utterance("Please help me make a money transfer")
                utterance("Okay thats cool please make a fund transfer")
                utterance("Make a transfer of <amount>.") {
                    context("Banks_1.TransferMoney")
                }
                utterance("Great, let's make a transfer.")

                utterance("Make a transfer to <recipient_account_name>")
                utterance("I wanna make a transfer")
                utterance("send <amount> and give it to <recipient_account_name> and go with the <account_type> account") {
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
            },
            frame("io.opencui.core.SlotUpdate") {
                utterance("""change <originalSlot>""") {
                }
                utterance("""change from <oldValue>""") {
                }
                utterance("""change to <newValue>""") {
                }
                utterance("""change <index> value""") {
                }
                utterance("""change <originalSlot> to <newValue>""") {
                }
                utterance("""change <oldValue> to <newValue>""") {
                }
                utterance("""change <index> <originalSlot>""") {
                }
                utterance("""change <originalSlot> from <oldValue> to <newValue>""") {
                }
                utterance("""change <index> value from <oldValue> to <newValue>""") {
                }
                utterance("""change <index> <originalSlot> to <newValue>""") {
                }
                utterance("""change <index> <originalSlot> from <oldValue> to <newValue>""") {
                }
                utterance("""change <index> <originalSlot> from <oldValue>""") {
                }
                utterance("""change <originalSlot> from <oldValue>""") {
                }
                utterance("""change <index> value to <newValue>""") {
                }
                utterance("""change <index> value of <oldValue>""") {
                }
              },
            frame("me.test.abstractEntity_1007.FoodOrdering") {
                utterance("""test""") {
                }
                utterance("""order <dish>""") {
                }
            }
        )

        override val entityTypes: Map<String, EntityType> = mapOf(
            "city" to entityType("city") {
                recognizer("ListRecognizer")
                entity("beijing", "bei jing", "shou du")
                entity("label", "expr1", "expr2")
            },
            "io.opencui.core.SlotType" to entityType("io.opencui.core.SlotType") {
                recognizer("ListRecognizer")
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

        override val frameSlotMetas: Map<String, List<DUSlotMeta>> = mapOf(
            "me.test.abstractEntity_1007.FoodOrdering" to listOf(
                DUSlotMeta(
                    label = "dish", isMultiValue = false, type = "me.test.abstractEntity_1007.Dish",
                    isHead = false, triggers = listOf("dish item", )
                )
            ),
            "io.opencui.core.SlotUpdate" to listOf(
                DUSlotMeta(label = "originalSlot", isMultiValue = false, type = "io.opencui.core.SlotType",
                  isHead = false, triggers = listOf("one", )),
                DUSlotMeta(label = "oldValue", isMultiValue = false, type = "T", isHead = false, triggers =
                  listOf()),
                DUSlotMeta(label = "index", isMultiValue = false, type = "io.opencui.core.Ordinal", isHead =
                  false, triggers = listOf("index", )),
                DUSlotMeta(label = "newValue", isMultiValue = false, type = "T", isHead = false, triggers =
                  listOf()),
                DUSlotMeta(label = "confirm", isMultiValue = false, type =
                  "io.opencui.core.confirmation.IStatus", isHead = false, triggers = listOf()),
                ),
        )

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

    val stateTracker = BertStateTracker(
            duMeta,
            32,
            3,
            0.5f,
            0.1f,
            0.5f
    )

    private val normalizers = listOf(ListRecognizerBuilder()(duMeta))

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
        println(value)
        assert(!value.leaf)
    }

    @Test
    fun testMatchIntent() {
        val frameEvents = stateTracker.convert("s", "order house special")
        println("frame events: $frameEvents")
        assertEquals(frameEvents.size, 1)
        val entityEvents = frameEvents[0].activeEntitySlots
        assertEquals(entityEvents.size, 1 )
        val longForm = """EntityEvent(value="me.test.abstractEntity_1007.HouseSpecial", attribute=dish, isLeaf=false, type=me.test.abstractEntity_1007.VirtualDish)"""
        assertEquals(entityEvents[0].toLongForm(), longForm)
    }

    @Test
    fun testTypeExprSegment() {
        val expressions = duMeta.expressionsByFrame["io.opencui.core.SlotUpdate"]!!
        val expr = Expression.segment(expressions[4].toMetaExpression(), "io.opencui.core.SlotUpdate")
        println(expr)
        assertEquals(expr.frame, "io.opencui.core.SlotUpdate")
        assertEquals(expr.segments.toString(), "[ExprSegment(expr=change, start=0, end=7), MetaSegment(meta=io.opencui.core.SlotType, start=7, end=35), ExprSegment(expr=to, start=35, end=39), MetaSegment(meta=T, start=39, end=44)]")

        val expr1 = Expression.segment(expressions[4].utterance, "io.opencui.core.SlotUpdate")
        println(expr1)
        assertEquals(expr1.segments.toString(), "[ExprSegment(expr=change, start=0, end=7), MetaSegment(meta=originalSlot, start=7, end=21), ExprSegment(expr=to, start=21, end=25), MetaSegment(meta=newValue, start=25, end=35)]")
    }
}


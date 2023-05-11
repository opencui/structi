package io.opencui.du

import com.fasterxml.jackson.databind.node.ObjectNode
import io.opencui.core.IChatbot
import io.opencui.core.RoutingInfo
import io.opencui.core.da.DialogActRewriter
import java.lang.Class
import kotlin.String
import kotlin.collections.List
import kotlin.collections.Map
import kotlin.collections.MutableList
import kotlin.reflect.KClass
import kotlin.test.assertEquals

import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime


public data class SmallTableAgent(
  public val user: String?
) : IChatbot() {
  public override val duMeta: DUMeta
    public get() = SmallTableAgent.duMeta

  public override val stateTracker: IStateTracker
    public get() = SmallTableAgent.stateTracker

  public override val rewriteRules: MutableList<KClass<out DialogActRewriter>> = mutableListOf()

  public override val routing: Map<String, RoutingInfo> = mapOf()
  init {
    rewriteRules += Class.forName("io.opencui.core.da.SlotOfferSepInformConfirmRule").kotlin as
        KClass<out DialogActRewriter>
  }

  public constructor() : this("")

  public companion object {
    public val duMeta: DUMeta = loadDUMetaDsl(en, SmallTableAgent::class.java.classLoader, "me.sean",
        "reservationApp_v4_copy", "en", "746395988637257728", "271", "Asia/Shanghai")

    public val stateTracker: IStateTracker = BertStateTracker(duMeta)
  }
}

public object en : LangPack {
  public override val frames: List<ObjectNode> = listOf(frame("io.opencui.core.CleanSession") {
        utterance("""Clean session, please.""") {
        }
        utterance("""Clean session""") {
        }
        utterance("""restart the session.""") {
        }
      }
      , frame("io.opencui.core.DontCare") {
        utterance("""I don't mind""") {
        }
        utterance("""I do not really care""") {
        }
        utterance("""as you like""") {
        }
        utterance("""anything will do""") {
        }
      }
      , frame("io.opencui.core.EntityType") {
        utterance("""any <slot>""") {
          context("io.opencui.core.DontCare", "slot")
        }
      }
      , frame("io.opencui.core.confirmation.Yes") {
        utterance("""yes""") {
        }
        utterance("""confirmed""") {
        }
        utterance("""Yes""") {
        }
        utterance("""True""") {
        }
        utterance("""Yeap""") {
        }
        utterance("""yes""") {
          context("io.opencui.core.confirmation.IStatus", "")
          label("confirmation")
        }
        utterance("""yes""") {
          context("me.demo.reservation_v2.MakeReservation", "date")
          label("confirmation")
        }
        utterance("""yes""") {
          context("io.opencui.core.confirmation.IStatus", "")
          label("confirmation")
        }
      }
      , frame("io.opencui.core.confirmation.No") {
        utterance("""no""") {
        }
        utterance("""No""") {
        }
        utterance("""False""") {
        }
        utterance("""Nope""") {
        }
        utterance("""no""") {
          context("io.opencui.core.confirmation.IStatus", "")
          label("confirmation")
        }
        utterance("""no""") {
          context("me.demo.reservation_v2.MakeReservation", "date")
          label("confirmation")
        }
        utterance("""no""") {
          context("io.opencui.core.confirmation.IStatus", "")
          label("confirmation")
        }
      }
      , frame("io.opencui.core.hasMore.No") {
        utterance("""nope""") {
        }
        utterance("""that is all""") {
        }
        utterance("""no""") {
        }
        utterance("""I am good""") {
        }
        utterance("""No""") {
        }
        utterance("""False""") {
        }
        utterance("""Nope""") {
        }
      }
      , frame("io.opencui.core.hasMore.Yes") {
        utterance("""yes""") {
        }
        utterance("""right""") {
        }
        utterance("""Yes""") {
        }
        utterance("""True""") {
        }
        utterance("""Yeap""") {
        }
      }
      , frame("io.opencui.core.booleanGate.Yes") {
        utterance("""yes""") {
        }
        utterance("""of course""") {
        }
        utterance("""sure""") {
        }
        utterance("""sure thing""") {
        }
        utterance("""ok""") {
        }
        utterance("""will do""") {
        }
        utterance("""Yes""") {
        }
        utterance("""True""") {
        }
        utterance("""Yeap""") {
        }
      }
      , frame("io.opencui.core.booleanGate.No") {
        utterance("""no""") {
        }
        utterance("""nope""") {
        }
        utterance("""of course not""") {
        }
        utterance("""no I don't""") {
        }
        utterance("""I don't want it""") {
        }
        utterance("""No""") {
        }
        utterance("""False""") {
        }
        utterance("""Nope""") {
        }
      }
      , frame("io.opencui.core.NextPage") {
        utterance("""next page""") {
        }
        utterance("""next please""") {
        }
        utterance("""skip to next""") {
        }
      }
      , frame("io.opencui.core.PreviousPage") {
        utterance("""previous page""") {
        }
        utterance("""go back to previous""") {
        }
        utterance("""skip backward""") {
        }
        utterance("""go back""") {
        }
      }
      , frame("io.opencui.core.Ordinal") {
        utterance("""The <index> one""") {
          context("io.opencui.core.PagedSelectable", "index")
        }
        utterance("""I like the <index> one.""") {
          context("io.opencui.core.PagedSelectable", "index")
        }
        utterance("""I like that <index> one.""") {
          context("io.opencui.core.PagedSelectable", "index")
        }
        utterance("""The <index> one is good.""") {
          context("io.opencui.core.PagedSelectable", "index")
        }
      }
      , frame("io.opencui.core.AbortIntent") {
        utterance("""I'd like to cancel <intentType> now""") {
        }
        utterance("""I do not want to <intentType> now""") {
        }
      }
      , frame("io.opencui.core.GetLiveAgent") {
        utterance("""live agent, please.""") {
        }
        utterance("""can I talk to a real person?""") {
        }
      }
      , frame("io.opencui.core.SlotUpdate") {
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
      }
      , frame("me.demo.reservation_v2.MakeReservation") {
        utterance("""I want to book a table""") {
        }
        utterance("""make a reservation""") {
        }
        utterance("""I want to place an order""") {
        }
        utterance("""I want to book a table <date>""") {
        }
        utterance("""I want to book a table at <time>""") {
        }
        utterance("""I want to book a <tableType>""") {
        }
      }
      , frame("me.demo.reservation_v2.ViewReservation") {
        utterance("""view reservation""") {
        }
      }
      , frame("me.demo.reservation_v2.CancelReservation") {
        utterance("""cancel reservation""") {
        }
        utterance("""I want to cancel my reservation""") {
        }
      }
      , frame("me.sean.reservationApp_v4_copy.Greeting") {
        utterance("""just going to say hi""") {
        }
        utterance("""heya""") {
        }
        utterance("""howdy""") {
        }
        utterance("""hey there""") {
        }
        utterance("""hi there""") {
        }
        utterance("""long time no see""") {
        }
        utterance("""hey""") {
        }
        utterance("""hello""") {
        }
      }
      )

  public override val entityTypes: Map<String, EntityType> = mapOf("kotlin.Int" to
      entityType("kotlin.Int") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "kotlin.Float" to entityType("kotlin.Float") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "kotlin.String" to entityType("kotlin.String") {
        children(listOf())
      }
      , 
      "kotlin.Boolean" to entityType("kotlin.Boolean") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("true","Yes","True","Yeap")
        entity("false","No","False","Nope")
      }
      , 
      "kotlin.Unit" to entityType("kotlin.Unit") {
        children(listOf())
      }
      , 
      "java.time.LocalDateTime" to entityType("java.time.LocalDateTime") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "java.time.Year" to entityType("java.time.Year") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "java.time.YearMonth" to entityType("java.time.YearMonth") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "java.time.LocalDate" to entityType("java.time.LocalDate") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "java.time.LocalTime" to entityType("java.time.LocalTime") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "java.time.DayOfWeek" to entityType("java.time.DayOfWeek") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("1","Mon.","Monday")
        entity("2","Tue.","Tuesday")
        entity("3","Wed.","Wednesday")
        entity("4","Thu.","Thursday")
        entity("5","Fri.","Friday")
        entity("6","Sat.","Saturday ")
        entity("7","Sun.","Sunday")
      }
      , 
      "kotlin.Any" to entityType("kotlin.Any") {
        children(listOf())
      }
      , 
      "io.opencui.core.Email" to entityType("io.opencui.core.Email") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "io.opencui.core.PhoneNumber" to entityType("io.opencui.core.PhoneNumber") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "io.opencui.core.Ordinal" to entityType("io.opencui.core.Ordinal") {
        children(listOf())
        recognizer("DucklingRecognizer")
      }
      , 
      "io.opencui.core.Currency" to entityType("io.opencui.core.Currency") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("USD","dollars","USD","US Dollar ")
        entity("GBP","pounds","GBP","Great British Pound")
        entity("CNY","Chinese yuan","China Yuan Renminbi","RMB")
        entity("EUR","Euro","EUR")
        entity("HDK","HDK","Hong Kong Dollar")
      }
      , 
      "io.opencui.core.FrameType" to entityType("io.opencui.core.FrameType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      , 
      "io.opencui.core.EntityType" to entityType("io.opencui.core.EntityType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      , 
      "io.opencui.core.SlotType" to entityType("io.opencui.core.SlotType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      , 
      "io.opencui.core.PromptMode" to entityType("io.opencui.core.PromptMode") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("General")
        entity("Repeat")
      }
      ,
      "io.opencui.core.FillState" to entityType("io.opencui.core.FillState") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("SlotInit")
      }
      , 
      "io.opencui.core.FailType" to entityType("io.opencui.core.FailType") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      , 
      "me.demo.reservation_v2.TableType" to entityType("me.demo.reservation_v2.TableType") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("small","small table","small")
        entity("medium","medium table","medium")
        entity("large","large table","large")
      }
      )

  public override val frameSlotMetas: Map<String, List<DUSlotMeta>> =
      mapOf("io.opencui.core.PagedSelectable" to listOf(
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.opencui.core.Ordinal", isHead =
          false, triggers = listOf("index", )),
      ),
      "io.opencui.core.IDonotGetIt" to listOf(
      ),
      "io.opencui.core.IDonotKnowWhatToDo" to listOf(
      ),
      "io.opencui.core.AbortIntent" to listOf(
      DUSlotMeta(label = "intentType", isMultiValue = false, type = "io.opencui.core.FrameType",
          isHead = false, triggers = listOf("intent", )),
      DUSlotMeta(label = "intent", isMultiValue = false, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.GetLiveAgent" to listOf(
      ),
      "io.opencui.core.BadCandidate" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "io.opencui.core.SlotType", isHead
          = false, triggers = listOf()),
      ),
      "io.opencui.core.BadIndex" to listOf(
      DUSlotMeta(label = "index", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.core.ConfirmationNo" to listOf(
      ),
      "io.opencui.core.ResumeIntent" to listOf(
      DUSlotMeta(label = "intent", isMultiValue = false, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.SlotUpdate" to listOf(
      DUSlotMeta(label = "originalSlot", isMultiValue = false, type = "io.opencui.core.SlotType",
          isHead = false, triggers = listOf("one", )),
      DUSlotMeta(label = "oldValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.opencui.core.Ordinal", isHead =
          false, triggers = listOf("index", )),
      DUSlotMeta(label = "newValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf("new <T>")),
      DUSlotMeta(label = "confirm", isMultiValue = false, type =
          "io.opencui.core.confirmation.IStatus", isHead = false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotRequest" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotRequestMore" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotNotifyFailure" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "failType", isMultiValue = false, type = "io.opencui.core.FailType", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotOffer" to listOf(
      DUSlotMeta(label = "value", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotOfferSepInform" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotOfferZepInform" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.FrameInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.opencui.core.da.SlotGate" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.FrameOffer" to listOf(
      DUSlotMeta(label = "value", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.core.da.FrameOfferSepInform" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.core.da.FrameOfferZepInform" to listOf(
      DUSlotMeta(label = "frameType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.core.da.FrameConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.opencui.core.da.UserDefinedInform" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.opencui.core.da.SlotOfferSepInformConfirm" to listOf(
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.da.SlotOfferSepInformConfirmRule" to listOf(
      DUSlotMeta(label = "slot0", isMultiValue = false, type =
          "io.opencui.core.da.SlotOfferSepInform", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "slot1", isMultiValue = false, type = "io.opencui.core.da.SlotConfirm",
          isHead = false, triggers = listOf()),
      ),
      "me.demo.reservation_v2.MakeReservation" to listOf(
      DUSlotMeta(label = "userIdentifier", isMultiValue = false, type =
          "io.opencui.core.user.UserIdentifier", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "reservationId", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf("reservation ID", )),
      DUSlotMeta(label = "date", isMultiValue = false, type = "java.time.LocalDate", isHead = false,
          triggers = listOf("date", )),
      DUSlotMeta(label = "time", isMultiValue = false, type = "java.time.LocalTime", isHead = false,
          triggers = listOf("time", )),
      DUSlotMeta(label = "tableType", isMultiValue = false, type =
          "me.demo.reservation_v2.TableType", isHead = false, triggers = listOf("table type", )),
      ),
      "me.demo.reservation_v2.ViewReservation" to listOf(
      DUSlotMeta(label = "userIdentifier", isMultiValue = false, type =
          "io.opencui.core.user.UserIdentifier", isHead = false, triggers = listOf()),
      ),
      "me.demo.reservation_v2.CancelReservation" to listOf(
      DUSlotMeta(label = "userIdentifier", isMultiValue = false, type =
          "io.opencui.core.user.UserIdentifier", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "reservationInfo", isMultiValue = false, type =
          "me.demo.reservation_v2.ReservationInfo", isHead = false, triggers = listOf()),
      ),
      "me.sean.reservationApp_v4_copy.Main" to listOf(
      DUSlotMeta(label = "Greeting", isMultiValue = false, type =
          "me.sean.reservationApp_v4_copy.Greeting", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "skills", isMultiValue = true, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "Goodbye", isMultiValue = false, type =
          "me.sean.reservationApp_v4_copy.Goodbye", isHead = false, triggers = listOf()),
      ),
      "me.sean.reservationApp_v4_copy.Greeting" to listOf(
      ),
      "me.sean.reservationApp_v4_copy.Goodbye" to listOf(
      ),
      "kotlin.Pair" to listOf(
      ),
      "io.opencui.core.IIntent" to listOf(
      ),
      "io.opencui.core.IContact" to listOf(
      DUSlotMeta(label = "channel", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("channel", )),
      DUSlotMeta(label = "id", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("id", )),
      ),
      "io.opencui.core.CleanSession" to listOf(
      ),
      "io.opencui.core.DontCare" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "io.opencui.core.EntityType", isHead =
          false, triggers = listOf("slot", )),
      ),
      "io.opencui.core.confirmation.IStatus" to listOf(
      ),
      "io.opencui.core.confirmation.Yes" to listOf(
      ),
      "io.opencui.core.confirmation.No" to listOf(
      ),
      "io.opencui.core.AmountOfMoney" to listOf(
      ),
      "io.opencui.core.hasMore.IStatus" to listOf(
      ),
      "io.opencui.core.hasMore.No" to listOf(
      ),
      "io.opencui.core.HasMore" to listOf(
      DUSlotMeta(label = "status", isMultiValue = false, type = "io.opencui.core.hasMore.IStatus",
          isHead = false, triggers = listOf()),
      ),
      "io.opencui.core.hasMore.Yes" to listOf(
      ),
      "io.opencui.core.Companion" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.companion.Not" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.companion.Or" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.booleanGate.IStatus" to listOf(
      ),
      "io.opencui.core.booleanGate.Yes" to listOf(
      ),
      "io.opencui.core.booleanGate.No" to listOf(
      ),
      "io.opencui.core.IntentClarification" to listOf(
      DUSlotMeta(label = "utterance", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("utterance", )),
      DUSlotMeta(label = "source", isMultiValue = true, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.ValueClarification" to listOf(
      DUSlotMeta(label = "source", isMultiValue = true, type = "T", isHead = false, triggers =
          listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "io.opencui.core.NextPage" to listOf(
      ),
      "io.opencui.core.PreviousPage" to listOf(
      ),
      "io.opencui.core.SlotInit" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("slot", )),
      ),
      "io.opencui.core.EntityRecord" to listOf(
      DUSlotMeta(label = "label", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("label", )),
      DUSlotMeta(label = "expressions", isMultiValue = true, type = "kotlin.String", isHead = false,
          triggers = listOf("expression", )),
      ),
      "io.opencui.core.user.UserIdentifier" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.user.IUserProfile" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "phone", isMultiValue = false, type = "io.opencui.core.PhoneNumber", isHead
          = false, triggers = listOf("phone", )),
      DUSlotMeta(label = "name", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("name", )),
      DUSlotMeta(label = "email", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf("email", )),
      DUSlotMeta(label = "userInputCode", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf("userInputCode", )),
      DUSlotMeta(label = "code", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf("code", )),
      ),
      "io.opencui.core.user.IUserIdentifier" to listOf(
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "channelLabel", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.IPersistent" to listOf(
      ),
      "io.opencui.core.ISingleton" to listOf(
      ),
      "io.opencui.core.IKernelIntent" to listOf(
      ),
      "io.opencui.core.ITransactionalIntent" to listOf(
      ),
      "io.opencui.core.That" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "T", isHead = true, triggers =
          listOf()),
      ),
      "io.opencui.core.SlotClarification" to listOf(
      DUSlotMeta(label = "mentionedSource", isMultiValue = false, type = "io.opencui.core.Cell",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "source", isMultiValue = true, type = "io.opencui.core.Cell", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "target", isMultiValue = false, type = "io.opencui.core.Cell", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.Cell" to listOf(
      DUSlotMeta(label = "originalSlot", isMultiValue = false, type = "io.opencui.core.SlotType",
          isHead = false, triggers = listOf("originalSlot", )),
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.opencui.core.Ordinal", isHead =
          false, triggers = listOf("index", )),
      ),
      "io.opencui.core.UserSession" to listOf(
      DUSlotMeta(label = "chatbot", isMultiValue = false, type = "io.opencui.core.IChatbot", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "channelType", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.IChatbot" to listOf(
      ),
      "io.opencui.core.IFrame" to listOf(
      ),
      "me.demo.reservation_v2.ReservationInfo" to listOf(
      DUSlotMeta(label = "reservationId", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf("reservation ID", )),
      DUSlotMeta(label = "date", isMultiValue = false, type = "java.time.LocalDate", isHead = false,
          triggers = listOf("date", )),
      DUSlotMeta(label = "time", isMultiValue = false, type = "java.time.LocalTime", isHead = false,
          triggers = listOf("time", )),
      DUSlotMeta(label = "tableType", isMultiValue = false, type =
          "me.demo.reservation_v2.TableType", isHead = false, triggers = listOf("table type", )),
      ),
      "me.demo.reservation_v2.TableInfo" to listOf(
      DUSlotMeta(label = "date", isMultiValue = false, type = "java.time.LocalDate", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "startTime", isMultiValue = false, type = "java.time.LocalTime", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "endTime", isMultiValue = false, type = "java.time.LocalTime", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "tableType", isMultiValue = false, type =
          "me.demo.reservation_v2.TableType", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "minGuest", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "maxGuest", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.provider.IConnection" to listOf(
      ),
      "io.opencui.provider.PostgrestConnection" to listOf(
      ),
      "io.opencui.provider.GoogleSheetsConnection" to listOf(
      ),
      "io.opencui.provider.RestfulConnection" to listOf(
      ),
      "me.demo.reservationProvider_v4.NumOfDays" to listOf(
      DUSlotMeta(label = "minNum", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "maxNum", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.demo.reservationProvider_v4.Reservation" to listOf(
      DUSlotMeta(label = "userId", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "date", isMultiValue = false, type = "java.time.LocalDate", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "time", isMultiValue = false, type = "java.time.LocalTime", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "tableType", isMultiValue = false, type =
          "me.demo.reservation_v2.TableType", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "status", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.demo.reservationProvider_v4.TableStatus" to listOf(
      DUSlotMeta(label = "date", isMultiValue = false, type = "java.time.LocalDate", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "tableType", isMultiValue = false, type =
          "me.demo.reservation_v2.TableType", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "minGuest", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "maxGuest", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "startTime", isMultiValue = false, type = "java.time.LocalTime", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "endTime", isMultiValue = false, type = "java.time.LocalTime", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "quantity", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "io.opencui.provider.ScalarContainer" to listOf(
      DUSlotMeta(label = "returnValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      )

  public override val typeAlias: Map<String, List<String>> = mapOf("io.opencui.core.Email" to
      listOf("Email"),
      "io.opencui.core.Currency" to listOf("Currency"),
      "io.opencui.core.FrameType" to listOf("Intent Name"),
      "io.opencui.core.EntityType" to listOf("Entity Name"),
      "io.opencui.core.SlotType" to listOf("Slot Name"),
      "io.opencui.core.PromptMode" to listOf("Prompt Mode"),
      "io.opencui.core.Language" to listOf("language"),
      "io.opencui.core.Country" to listOf("country"),
      "io.opencui.core.FillState" to listOf("FillState"),
      "me.demo.reservation_v2.TableType" to listOf("Table Type"),
      "io.opencui.core.IDonotGetIt" to listOf("I don't get it"),
      "io.opencui.core.IDonotKnowWhatToDo" to listOf("I don't know what to do"),
      "io.opencui.core.AbortIntent" to listOf("Abort Intent"),
      "io.opencui.core.GetLiveAgent" to listOf("Hand off"),
      "io.opencui.core.ResumeIntent" to listOf("ResumeIntent"),
      "java.time.LocalDate" to listOf("date"),
      "java.time.LocalTime" to listOf("time"),
      "me.demo.reservation_v2.MakeReservation" to listOf("Make Reservation"),
      "me.demo.reservation_v2.ViewReservation" to listOf("View Reservation"),
      "me.demo.reservation_v2.CancelReservation" to listOf("Cancel Reservation"),
      "me.sean.reservationApp_v4_copy.Greeting" to listOf("Greeting"),
      "me.sean.reservationApp_v4_copy.Goodbye" to listOf("Goodbye"),
      "io.opencui.core.DontCare" to listOf("DontCare"),
      "io.opencui.core.AmountOfMoney" to listOf("AmountOfMoney"),
      "io.opencui.core.Companion" to listOf("Companion"),
      "io.opencui.core.companion.Not" to listOf("companion.Not"),
      "io.opencui.core.companion.Or" to listOf("companion.Or"),
      "io.opencui.core.NextPage" to listOf("next page"),
      "io.opencui.core.PreviousPage" to listOf("previous page"),
      "me.demo.reservation_v2.ReservationInfo" to listOf("Reservation Information"),
      )
}


class SmallTableDslTest() : DuTestHelper() {

    val agent = SmallTableAgent.duMeta
    private val normalizers = listOf(ListRecognizer(agent))

    val stateTracker = BertStateTracker(
            agent,
            32,
            3,
            0.5f,
            0.1f,
            0.5f
    )

    @Test
    fun testSmallTable() {
        val frameEvents = stateTracker.convert("s", "I want to book a small table")
        assertEquals(frameEvents.toString(), """[FrameEvent(type=MakeReservation, slots=[EntityEvent(value="small", attribute=tableType)], frames=[], packageName=me.demo.reservation_v2)]""")
    }

    @Test
    fun testSlotUpdate() {
        val frameEvents = stateTracker.convert(
            "s",
            "change time to 7:00pm",
            DialogExpectations(ExpectedFrame("me.demo.reservation_v2.MakeReservation", "tableType"))
        )
        println(frameEvents)
        assertEquals(frameEvents.toString(), """[FrameEvent(type=SlotUpdate, slots=[EntityEvent(value="me.demo.reservation_v2.ReservationInfo.time", attribute=originalSlot), EntityEvent(value="19:00:00", attribute=newValue)], frames=[], packageName=io.opencui.core)]""")
    }

    @Test
    fun testSurroundingWords() {
        val surroundings = extractSlotSurroundingWords(agent.expressionsByFrame, LanguageAnalyzer.get("en")!!)
        assertEquals(surroundings.first.size, 10)
    }

    @Test
    fun testDateUpdateExact() {
        val frameEvents = stateTracker.convert(
            "s",
            "change date to tomorrow",
            DialogExpectations(ExpectedFrame("me.demo.reservation_v2.MakeReservation", "tableType"))
        )
        val tmr = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1)
        assertEquals(frameEvents.size, 1)
        assertEquals(frameEvents[0].type, "SlotUpdate")
        assertEquals(frameEvents.toString(), """[FrameEvent(type=SlotUpdate, slots=[EntityEvent(value="me.demo.reservation_v2.ReservationInfo.date", attribute=originalSlot), EntityEvent(value="$tmr", attribute=newValue)], frames=[], packageName=io.opencui.core)]""")
    }

    @Test
    fun testDateUpdateShort() {
        val frameEvents = stateTracker.convert(
            "s",
            "change to tomorrow",
            DialogExpectations(ExpectedFrame("me.demo.reservation_v2.MakeReservation", "tableType"))
        )
        val tmr = ZonedDateTime.now(ZoneId.of("Asia/Shanghai")).toLocalDate().plusDays(1)
        println(frameEvents)
        assertEquals(frameEvents.size, 1)
        assertEquals(frameEvents[0].type, "SlotUpdate")
        assertEquals(frameEvents.toString(), """[FrameEvent(type=SlotUpdate, slots=[EntityEvent(value="$tmr", attribute=newValue)], frames=[], packageName=io.opencui.core)]""")
    }

        @Test
    fun testDateUpdateInExact() {
        val frameEvents = stateTracker.convert(
            "s",
            "change time to 7:00pm please",
            DialogExpectations(ExpectedFrame("me.demo.reservation_v2.MakeReservation", "tableType"))
        )
        println(frameEvents)
        assertEquals(frameEvents.size, 1)
        assertEquals(frameEvents[0].type, "SlotUpdate")
        assertEquals(frameEvents.toString(), """[FrameEvent(type=SlotUpdate, slots=[EntityEvent(value="me.demo.reservation_v2.ReservationInfo.time", attribute=originalSlot), EntityEvent(value="19:00:00", attribute=newValue)], frames=[], packageName=io.opencui.core)]""")
    }
}

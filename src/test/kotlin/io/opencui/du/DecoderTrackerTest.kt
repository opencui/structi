package io.opencui.du

import com.fasterxml.jackson.databind.node.ObjectNode
import io.opencui.core.IChatbot
import io.opencui.core.RoutingInfo
import io.opencui.core.da.DialogActRewriter
import io.opencui.du.DUMeta.Companion.parseExpressions
import io.opencui.serialization.Json
import org.junit.Test
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.*
import kotlin.reflect.KClass
import kotlin.test.assertEquals

public object ens : LangPack {
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
      , frame("io.opencui.core.confirmation.IStatus") {
        utterance("""yes""") {
          label("core.confirmation.Yes")
        }
        utterance("""confirmed""") {
          label("core.confirmation.Yes")
        }
        utterance("""no""") {
          label("core.confirmation.No")
        }
        utterance("""Yes, that's fine""") {
          context("me.test.foodOrderingModule.FoodOrdering", "")
          label("core.confirmation.Yes")
        }
        utterance("""no""") {
          context("me.test.foodOrderingModule.FoodOrdering", "")
          label("core.confirmation.No")
        }
        utterance("""Yes, that's fine""") {
          context("me.test.foodOrderingModule.FoodOrdering", "")
          label("core.confirmation.Yes")
        }
        utterance("""no""") {
          context("me.test.foodOrderingModule.FoodOrdering", "")
          label("core.confirmation.No")
        }
      }
      , frame("io.opencui.core.confirmation.Yes") {
        utterance("""Yes""") {
        }
        utterance("""True""") {
        }
        utterance("""Yeap""") {
        }
      }
      , frame("io.opencui.core.confirmation.No") {
        utterance("""No""") {
        }
        utterance("""False""") {
        }
        utterance("""Nope""") {
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
      , frame("io.opencui.core.companion.GreaterThan") {
        utterance("""Greater Than""") {
        }
      }
      , frame("io.opencui.core.companion.LessThan") {
        utterance("""Less Than""") {
        }
      }
      , frame("io.opencui.core.companion.GreaterThanOrEqualTo") {
        utterance("""Greater Than Or Equal To""") {
        }
      }
      , frame("io.opencui.core.companion.LessThanOrEqualTo") {
        utterance("""Less Than Or Equal To""") {
        }
      }
      , frame("me.test.foodOrderingModule.ViewCart") {
        utterance("""What did I order before?""") {
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
      , frame("me.test.foodOrderingApp.Greeting") {
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
      , frame("me.test.foodOrderingModule.FoodOrdering") {
        utterance("""order food""") {
        }
        utterance("""Hi, I'd like to place an order for pickup.""") {
        }
        utterance("""I'd like to order some food please.""") {
        }
        utterance("""Can we place an order with you?""") {
        }
        utterance("""Hi there, could I grab a bite to eat?""") {
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
        entity("MONDAY","Mon","Monday","Mondays")
        entity("TUESDAY","Tue","Tuesday","Tuesdays")
        entity("WEDNESDAY","Wed","Wednesday","Wednesdays")
        entity("THURSDAY","Thu","Thursday","Thursdays")
        entity("FRIDAY","Fri","Friday","Fridays")
        entity("SATURDAY","Sat","Saturday ","Saturdays")
        entity("SUNDAY","Sun","Sunday","Sundays")
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
        entity("kotlin.Pair")
        entity("io.opencui.core.IIntent","IIntent")
        entity("io.opencui.core.IContact","IContact")
        entity("io.opencui.core.CleanSession","clean session")
        entity("io.opencui.core.DontCare","DontCare")
        entity("io.opencui.core.confirmation.IStatus","confirmation.Status")
        entity("io.opencui.core.confirmation.Yes","confirmation.Yes")
        entity("io.opencui.core.confirmation.No","confirmation.No")
        entity("io.opencui.core.AmountOfMoney","AmountOfMoney")
        entity("io.opencui.core.hasMore.IStatus","hasMore.Status")
        entity("io.opencui.core.hasMore.No","hasMore.No")
        entity("io.opencui.core.HasMore","HasMore")
        entity("io.opencui.core.hasMore.Yes","hasMore.Yes")
        entity("io.opencui.core.Companion","Companion")
        entity("io.opencui.core.companion.Not","companion.Not")
        entity("io.opencui.core.companion.Or","companion.Or")
        entity("io.opencui.core.booleanGate.IStatus","BooleanGate Status")
        entity("io.opencui.core.booleanGate.Yes","BooleanGate Affirmative Expressions")
        entity("io.opencui.core.booleanGate.No","BooleanGate Negative  Expressions")
        entity("io.opencui.core.IntentClarification","intent clarification")
        entity("io.opencui.core.ValueClarification","Value Clarification")
        entity("io.opencui.core.NextPage","next page")
        entity("io.opencui.core.PreviousPage","previous page")
        entity("io.opencui.core.SlotInit","SlotInit")
        entity("io.opencui.core.EntityRecord","entity record")
        entity("io.opencui.core.user.UserIdentifier")
        entity("io.opencui.core.user.IUserProfile","user.IUserProfile")
        entity("io.opencui.core.user.IUserIdentifier")
        entity("io.opencui.core.IPersistent")
        entity("io.opencui.core.ISingleton")
        entity("io.opencui.core.IKernelIntent")
        entity("io.opencui.core.ITransactionalIntent")
        entity("io.opencui.core.That")
        entity("io.opencui.core.SlotClarification","SlotClarification")
        entity("io.opencui.core.Cell","Cell")
        entity("io.opencui.core.UserSession")
        entity("io.opencui.core.IChatbot")
        entity("io.opencui.core.IFrame")
        entity("io.opencui.core.companion.GreaterThan","Greater Than")
        entity("io.opencui.core.companion.LessThan","Less Than")
        entity("io.opencui.core.companion.GreaterThanOrEqualTo","Greater Than Or Equal To")
        entity("io.opencui.core.companion.LessThanOrEqualTo","Less Than Or Equal To")
        entity("io.opencui.core.SlotValue","SlotValue")
        entity("me.test.foodOrdering.IOrderItem")
        entity("me.test.foodOrderingModule.Dish","Dish")
        entity("me.test.foodOrderingModule.ViewCart","View cart")
        entity("me.test.foodOrderingModule.CheckOut","Check out")
        entity("io.opencui.provider.IConnection")
        entity("io.opencui.provider.PostgrestConnection")
        entity("io.opencui.provider.GoogleSheetsConnection")
        entity("io.opencui.provider.RestfulConnection")
        entity("io.opencui.provider.ScalarContainer")
        entity("me.test.foodOrderingProvider.IntWrapper")
        entity("me.test.foodOrderingProvider.DishItems")
        entity("me.test.foodOrderingProvider.Customization")
        entity("me.test.foodOrderingProvider.Options")
        entity("me.test.foodOrderingProvider.Orders")
        entity("me.test.foodOrderingProvider.OrderItems")
        entity("me.test.foodOrderingProvider.DishNameWrapper")
        entity("me.test.foodOrderingProvider.SizeWrapper")
        entity("me.test.foodOrderingProvider.CustomizationWrapper")
        entity("me.test.foodOrderingProvider.OptionWrapper")
        entity("io.opencui.core.PagedSelectable","paged selectable")
        entity("io.opencui.core.IDonotGetIt","I don't get it")
        entity("io.opencui.core.IDonotKnowWhatToDo","I don't know what to do")
        entity("io.opencui.core.AbortIntent","Abort Intent")
        entity("io.opencui.core.GetLiveAgent","Hand off")
        entity("io.opencui.core.BadCandidate")
        entity("io.opencui.core.BadIndex")
        entity("io.opencui.core.ConfirmationNo")
        entity("io.opencui.core.ResumeIntent","ResumeIntent")
        entity("io.opencui.core.SlotUpdate","SlotUpdate")
        entity("io.opencui.core.da.SlotRequest")
        entity("io.opencui.core.da.SlotRequestMore")
        entity("io.opencui.core.da.SlotNotifyFailure")
        entity("io.opencui.core.da.SlotOffer")
        entity("io.opencui.core.da.SlotOfferSepInform")
        entity("io.opencui.core.da.SlotOfferZepInform")
        entity("io.opencui.core.da.SlotInform")
        entity("io.opencui.core.da.SlotConfirm")
        entity("io.opencui.core.da.FrameInform")
        entity("io.opencui.core.da.SlotGate")
        entity("io.opencui.core.da.FrameOffer")
        entity("io.opencui.core.da.FrameOfferSepInform")
        entity("io.opencui.core.da.FrameOfferZepInform")
        entity("io.opencui.core.da.FrameConfirm")
        entity("io.opencui.core.da.UserDefinedInform")
        entity("io.opencui.core.da.SlotOfferSepInformConfirm")
        entity("io.opencui.core.da.SlotOfferSepInformConfirmRule")
        entity("me.test.foodOrderingApp.Main","Main")
        entity("me.test.foodOrderingApp.Greeting","Greeting")
        entity("me.test.foodOrderingApp.Goodbye","Goodbye")
        entity("io.opencui.core.SlotNotify")
        entity("io.opencui.core.TriggerComponentSkill","Trigger Component Skill")
        entity("io.opencui.core.System1","System1")
        entity("me.test.foodOrderingModule.FoodOrdering","Food ordering")
      }
      ,
      "io.opencui.core.EntityType" to entityType("io.opencui.core.EntityType") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("kotlin.Int","int")
        entity("kotlin.Float","float")
        entity("kotlin.String","string")
        entity("kotlin.Boolean","boolean")
        entity("kotlin.Unit","unit")
        entity("java.time.LocalDateTime","local date time")
        entity("java.time.Year","year")
        entity("java.time.YearMonth","year month")
        entity("java.time.LocalDate","local date")
        entity("java.time.LocalTime","loacl time")
        entity("java.time.DayOfWeek","day of week")
        entity("java.time.ZoneId","zone id")
        entity("kotlin.Any","any")
        entity("io.opencui.core.Email","Email")
        entity("io.opencui.core.PhoneNumber","Phone Number")
        entity("io.opencui.core.Ordinal","Ordinal")
        entity("io.opencui.core.Currency","Currency")
        entity("io.opencui.core.SlotType","Slot Name")
        entity("io.opencui.core.PromptMode","Prompt Mode")
        entity("io.opencui.core.Language","language")
        entity("io.opencui.core.Country","country")
        entity("io.opencui.core.FillState","FillState")
        entity("io.opencui.core.FailType","FailType")
        entity("java.time.OffsetDateTime","OffsetDateTime")
        entity("io.opencui.core.SlotEntity","Slot")
        entity("me.test.foodOrdering.Category")
        entity("me.test.foodOrdering.DishName")
        entity("me.test.foodOrdering.Size")
        entity("me.test.foodOrdering.Customization")
        entity("me.test.foodOrdering.Option")
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
      "java.time.OffsetDateTime" to entityType("java.time.OffsetDateTime") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "io.opencui.core.SlotEntity" to entityType("io.opencui.core.SlotEntity") {
        children(listOf())
        recognizer("ListRecognizer")
      }
      ,
      "me.test.foodOrdering.Category" to entityType("me.test.foodOrdering.Category") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("Pizza","Pizza")
        entity("Appetizer","Appetizer")
        entity("Drink","Drink")
      }
      ,
      "me.test.foodOrdering.DishName" to entityType("me.test.foodOrdering.DishName") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("PepperoniPizza","Pepperoni Pizza")
        entity("CheesePizza","Cheese Pizza")
        entity("EggplantPizza","Eggplant Pizza")
        entity("Fries","Fries")
        entity("GreekSalad","Greek Salad")
        entity("Coke","Coke")
        entity("Sprite","Sprite")
        entity("BottledWater","Bottled Water")
      }
      ,
      "me.test.foodOrdering.Size" to entityType("me.test.foodOrdering.Size") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("Small","Small")
        entity("Medium","Medium")
        entity("Large","Large")
        entity("Regular","Regular")
      }
      ,
      "me.test.foodOrdering.Customization" to entityType("me.test.foodOrdering.Customization") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("Topping","toppings")
      }
      ,
      "me.test.foodOrdering.Option" to entityType("me.test.foodOrdering.Option") {
        children(listOf())
        recognizer("ListRecognizer")
        entity("ExtraCheese","Extra Cheese")
        entity("Mushrooms","Mushrooms")
        entity("Sausage","Sausage")
        entity("CanadianBacon","Canadian Bacon")
        entity("AISauce","AI Sauce")
        entity("Peppers","Peppers")
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
          isHead = false, triggers = listOf("it", )),
      DUSlotMeta(label = "oldValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf("old value", )),
      DUSlotMeta(label = "index", isMultiValue = false, type = "io.opencui.core.Ordinal", isHead =
          false, triggers = listOf("index", )),
      DUSlotMeta(label = "newValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf("new value", )),
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
      "me.test.foodOrderingApp.Main" to listOf(
      DUSlotMeta(label = "Greeting", isMultiValue = false, type =
          "me.test.foodOrderingApp.Greeting", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "skills", isMultiValue = true, type = "io.opencui.core.IIntent", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "Goodbye", isMultiValue = false, type = "me.test.foodOrderingApp.Goodbye",
          isHead = false, triggers = listOf()),
      ),
      "me.test.foodOrderingApp.Greeting" to listOf(
      ),
      "me.test.foodOrderingApp.Goodbye" to listOf(
      ),
      "io.opencui.core.SlotNotify" to listOf(
      DUSlotMeta(label = "slotName", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "slotType", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "context", isMultiValue = true, type = "io.opencui.core.IFrame", isHead =
          false, triggers = listOf()),
      ),
      "io.opencui.core.TriggerComponentSkill" to listOf(
      DUSlotMeta(label = "compositeSkillName", isMultiValue = false, type =
          "io.opencui.core.FrameType", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "componentSkillName", isMultiValue = false, type =
          "io.opencui.core.FrameType", isHead = false, triggers = listOf()),
      ),
      "io.opencui.core.System1" to listOf(
      DUSlotMeta(label = "userIdentifier", isMultiValue = false, type =
          "io.opencui.core.user.UserIdentifier", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "reply", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingModule.FoodOrdering" to listOf(
      DUSlotMeta(label = "dishes", isMultiValue = true, type = "me.test.foodOrderingModule.Dish",
          isHead = false, triggers = listOf("dishes", )),
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
      "io.opencui.core.companion.GreaterThan" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.companion.LessThan" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.companion.GreaterThanOrEqualTo" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.companion.LessThanOrEqualTo" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.Any", isHead = false, triggers
          = listOf("slot", )),
      ),
      "io.opencui.core.SlotValue" to listOf(
      DUSlotMeta(label = "slot", isMultiValue = false, type = "kotlin.String", isHead = false,
          triggers = listOf()),
      DUSlotMeta(label = "value", isMultiValue = false, type = "kotlin.Any", isHead = false,
          triggers = listOf("value", )),
      ),
      "me.test.foodOrdering.IOrderItem" to listOf(
      DUSlotMeta(label = "category", isMultiValue = false, type = "me.test.foodOrdering.Category",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "name", isMultiValue = false, type = "me.test.foodOrdering.DishName",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "size", isMultiValue = false, type = "me.test.foodOrdering.Size", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "options", isMultiValue = true, type = "me.test.foodOrdering.Option",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "quantity", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingModule.Dish" to listOf(
      DUSlotMeta(label = "category", isMultiValue = false, type = "me.test.foodOrdering.Category",
          isHead = false, triggers = listOf("category", )),
      DUSlotMeta(label = "name", isMultiValue = false, type = "me.test.foodOrdering.DishName",
          isHead = false, triggers = listOf("dish name", )),
      DUSlotMeta(label = "size", isMultiValue = false, type = "me.test.foodOrdering.Size", isHead =
          false, triggers = listOf("size", )),
      DUSlotMeta(label = "customization", isMultiValue = false, type =
          "me.test.foodOrdering.Customization", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "isAddOption", isMultiValue = false, type = "kotlin.Boolean", isHead =
          false, triggers = listOf("whether to add options", )),
      DUSlotMeta(label = "options", isMultiValue = true, type = "me.test.foodOrdering.Option",
          isHead = false, triggers = listOf("options", )),
      DUSlotMeta(label = "quantity", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf("""
      |quantity
      |""".trimMargin(), )),
      ),
      "me.test.foodOrderingModule.ViewCart" to listOf(
      ),
      "me.test.foodOrderingModule.CheckOut" to listOf(
      ),
      "io.opencui.provider.IConnection" to listOf(
      ),
      "io.opencui.provider.PostgrestConnection" to listOf(
      ),
      "io.opencui.provider.GoogleSheetsConnection" to listOf(
      ),
      "io.opencui.provider.RestfulConnection" to listOf(
      ),
      "io.opencui.provider.ScalarContainer" to listOf(
      DUSlotMeta(label = "returnValue", isMultiValue = false, type = "T", isHead = false, triggers =
          listOf()),
      ),
      "me.test.foodOrderingProvider.IntWrapper" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.DishItems" to listOf(
      DUSlotMeta(label = "_id", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      DUSlotMeta(label = "_created_at", isMultiValue = false, type = "java.time.LocalDateTime",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "category", isMultiValue = false, type = "me.test.foodOrdering.Category",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "name", isMultiValue = false, type = "me.test.foodOrdering.DishName",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "size", isMultiValue = false, type = "me.test.foodOrdering.Size", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "amount", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.Customization" to listOf(
      DUSlotMeta(label = "_id", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      DUSlotMeta(label = "_created_at", isMultiValue = false, type = "java.time.LocalDateTime",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "category", isMultiValue = false, type = "me.test.foodOrdering.Category",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "name", isMultiValue = false, type = "me.test.foodOrdering.Customization",
          isHead = false, triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.Options" to listOf(
      DUSlotMeta(label = "_id", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      DUSlotMeta(label = "_created_at", isMultiValue = false, type = "java.time.LocalDateTime",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "customizationId", isMultiValue = false, type =
          "me.test.foodOrderingProvider.Customization", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "name", isMultiValue = false, type = "me.test.foodOrdering.Option", isHead
          = false, triggers = listOf()),
      DUSlotMeta(label = "amount", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.Orders" to listOf(
      DUSlotMeta(label = "_id", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      DUSlotMeta(label = "_created_at", isMultiValue = false, type = "java.time.LocalDateTime",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "orderStatus", isMultiValue = false, type = "kotlin.String", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "totalAmount", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.OrderItems" to listOf(
      DUSlotMeta(label = "_id", isMultiValue = false, type = "kotlin.Int", isHead = false, triggers
          = listOf()),
      DUSlotMeta(label = "_created_at", isMultiValue = false, type = "java.time.LocalDateTime",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "orderId", isMultiValue = false, type =
          "me.test.foodOrderingProvider.Orders", isHead = false, triggers = listOf()),
      DUSlotMeta(label = "category", isMultiValue = false, type = "me.test.foodOrdering.Category",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "dishName", isMultiValue = false, type = "me.test.foodOrdering.DishName",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "size", isMultiValue = false, type = "me.test.foodOrdering.Size", isHead =
          false, triggers = listOf()),
      DUSlotMeta(label = "options", isMultiValue = true, type = "me.test.foodOrdering.Option",
          isHead = false, triggers = listOf()),
      DUSlotMeta(label = "quantity", isMultiValue = false, type = "kotlin.Int", isHead = false,
          triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.DishNameWrapper" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "me.test.foodOrdering.DishName",
          isHead = false, triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.SizeWrapper" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "me.test.foodOrdering.Size", isHead =
          false, triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.CustomizationWrapper" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "me.test.foodOrdering.Customization",
          isHead = false, triggers = listOf()),
      ),
      "me.test.foodOrderingProvider.OptionWrapper" to listOf(
      DUSlotMeta(label = "value", isMultiValue = false, type = "me.test.foodOrdering.Option", isHead
          = false, triggers = listOf()),
      ),
      )

  public override val typeAlias: Map<String, List<String>> = mapOf("kotlin.Int" to listOf("",
      "int"),
      "kotlin.Float" to listOf("", "float"),
      "kotlin.String" to listOf("", "string"),
      "kotlin.Boolean" to listOf("", "boolean"),
      "kotlin.Unit" to listOf("", "unit"),
      "java.time.LocalDateTime" to listOf("", "local date time"),
      "java.time.Year" to listOf("", "year"),
      "java.time.YearMonth" to listOf("", "year month"),
      "java.time.LocalDate" to listOf("", "local date"),
      "java.time.LocalTime" to listOf("", "loacl time"),
      "java.time.DayOfWeek" to listOf("", "day of week"),
      "java.time.ZoneId" to listOf("", "zone id"),
      "kotlin.Any" to listOf("", "any"),
      "io.opencui.core.Email" to listOf("", "Email"),
      "io.opencui.core.PhoneNumber" to listOf("", "Phone Number"),
      "io.opencui.core.Ordinal" to listOf("", "Ordinal"),
      "io.opencui.core.Currency" to listOf("", "Currency"),
      "io.opencui.core.FrameType" to listOf("", "Frame Name"),
      "io.opencui.core.EntityType" to listOf("", "Entity Name"),
      "io.opencui.core.SlotType" to listOf("", "Slot Name"),
      "io.opencui.core.PromptMode" to listOf("", "Prompt Mode"),
      "io.opencui.core.Language" to listOf("", "language"),
      "io.opencui.core.Country" to listOf("", "country"),
      "io.opencui.core.FillState" to listOf("", "FillState"),
      "io.opencui.core.FailType" to listOf("", "FailType"),
      "java.time.OffsetDateTime" to listOf("", "OffsetDateTime"),
      "io.opencui.core.SlotEntity" to listOf("", "Slot"),
      "me.test.foodOrdering.Category" to listOf(""),
      "me.test.foodOrdering.DishName" to listOf(""),
      "me.test.foodOrdering.Size" to listOf(""),
      "me.test.foodOrdering.Customization" to listOf(""),
      "me.test.foodOrdering.Option" to listOf(""),
      "io.opencui.core.PagedSelectable" to listOf("", "paged selectable"),
      "io.opencui.core.IDonotGetIt" to listOf("", "I don't get it"),
      "io.opencui.core.IDonotKnowWhatToDo" to listOf("", "I don't know what to do"),
      "io.opencui.core.AbortIntent" to listOf("", "Abort Intent"),
      "io.opencui.core.GetLiveAgent" to listOf("", "Hand off"),
      "io.opencui.core.BadCandidate" to listOf(""),
      "io.opencui.core.BadIndex" to listOf(""),
      "io.opencui.core.ConfirmationNo" to listOf(""),
      "io.opencui.core.ResumeIntent" to listOf("", "ResumeIntent"),
      "io.opencui.core.SlotUpdate" to listOf("", "SlotUpdate"),
      "io.opencui.core.da.SlotRequest" to listOf(""),
      "io.opencui.core.da.SlotRequestMore" to listOf(""),
      "io.opencui.core.da.SlotNotifyFailure" to listOf(""),
      "io.opencui.core.da.SlotOffer" to listOf(""),
      "io.opencui.core.da.SlotOfferSepInform" to listOf(""),
      "io.opencui.core.da.SlotOfferZepInform" to listOf(""),
      "io.opencui.core.da.SlotInform" to listOf(""),
      "io.opencui.core.da.SlotConfirm" to listOf(""),
      "io.opencui.core.da.FrameInform" to listOf(""),
      "io.opencui.core.da.SlotGate" to listOf(""),
      "io.opencui.core.da.FrameOffer" to listOf(""),
      "io.opencui.core.da.FrameOfferSepInform" to listOf(""),
      "io.opencui.core.da.FrameOfferZepInform" to listOf(""),
      "io.opencui.core.da.FrameConfirm" to listOf(""),
      "io.opencui.core.da.UserDefinedInform" to listOf(""),
      "io.opencui.core.da.SlotOfferSepInformConfirm" to listOf(""),
      "io.opencui.core.da.SlotOfferSepInformConfirmRule" to listOf(""),
      "me.test.foodOrderingApp.Main" to listOf("", "Main"),
      "me.test.foodOrderingApp.Greeting" to listOf("", "Greeting"),
      "me.test.foodOrderingApp.Goodbye" to listOf("", "Goodbye"),
      "io.opencui.core.SlotNotify" to listOf(""),
      "io.opencui.core.TriggerComponentSkill" to listOf("", "Trigger Component Skill"),
      "io.opencui.core.System1" to listOf("", "System1"),
      "me.test.foodOrderingModule.FoodOrdering" to listOf("", "Food ordering"),
      "kotlin.Pair" to listOf(""),
      "io.opencui.core.IIntent" to listOf("", "IIntent"),
      "io.opencui.core.IContact" to listOf("", "IContact"),
      "io.opencui.core.CleanSession" to listOf("", "clean session"),
      "io.opencui.core.DontCare" to listOf("", "DontCare"),
      "io.opencui.core.confirmation.IStatus" to listOf("", "confirmation.Status"),
      "io.opencui.core.confirmation.Yes" to listOf("", "confirmation.Yes"),
      "io.opencui.core.confirmation.No" to listOf("", "confirmation.No"),
      "io.opencui.core.AmountOfMoney" to listOf("", "AmountOfMoney"),
      "io.opencui.core.hasMore.IStatus" to listOf("", "hasMore.Status"),
      "io.opencui.core.hasMore.No" to listOf("", "hasMore.No"),
      "io.opencui.core.HasMore" to listOf("", "HasMore"),
      "io.opencui.core.hasMore.Yes" to listOf("", "hasMore.Yes"),
      "io.opencui.core.Companion" to listOf("", "Companion"),
      "io.opencui.core.companion.Not" to listOf("", "companion.Not"),
      "io.opencui.core.companion.Or" to listOf("", "companion.Or"),
      "io.opencui.core.booleanGate.IStatus" to listOf("", "BooleanGate Status"),
      "io.opencui.core.booleanGate.Yes" to listOf("", "BooleanGate Affirmative Expressions"),
      "io.opencui.core.booleanGate.No" to listOf("", "BooleanGate Negative  Expressions"),
      "io.opencui.core.IntentClarification" to listOf("", "intent clarification"),
      "io.opencui.core.ValueClarification" to listOf("", "Value Clarification"),
      "io.opencui.core.NextPage" to listOf("", "next page"),
      "io.opencui.core.PreviousPage" to listOf("", "previous page"),
      "io.opencui.core.SlotInit" to listOf("", "SlotInit"),
      "io.opencui.core.EntityRecord" to listOf("", "entity record"),
      "io.opencui.core.user.UserIdentifier" to listOf(""),
      "io.opencui.core.user.IUserProfile" to listOf("", "user.IUserProfile"),
      "io.opencui.core.user.IUserIdentifier" to listOf(""),
      "io.opencui.core.IPersistent" to listOf(""),
      "io.opencui.core.ISingleton" to listOf(""),
      "io.opencui.core.IKernelIntent" to listOf(""),
      "io.opencui.core.ITransactionalIntent" to listOf(""),
      "io.opencui.core.That" to listOf(""),
      "io.opencui.core.SlotClarification" to listOf("", "SlotClarification"),
      "io.opencui.core.Cell" to listOf("", "Cell"),
      "io.opencui.core.UserSession" to listOf(""),
      "io.opencui.core.IChatbot" to listOf(""),
      "io.opencui.core.IFrame" to listOf(""),
      "io.opencui.core.companion.GreaterThan" to listOf("", "Greater Than"),
      "io.opencui.core.companion.LessThan" to listOf("", "Less Than"),
      "io.opencui.core.companion.GreaterThanOrEqualTo" to listOf("", "Greater Than Or Equal To"),
      "io.opencui.core.companion.LessThanOrEqualTo" to listOf("", "Less Than Or Equal To"),
      "io.opencui.core.SlotValue" to listOf("", "SlotValue"),
      "me.test.foodOrdering.IOrderItem" to listOf(""),
      "me.test.foodOrderingModule.Dish" to listOf("", "Dish"),
      "me.test.foodOrderingModule.ViewCart" to listOf("", "View cart"),
      "me.test.foodOrderingModule.CheckOut" to listOf("", "Check out"),
      "io.opencui.provider.IConnection" to listOf(""),
      "io.opencui.provider.PostgrestConnection" to listOf(""),
      "io.opencui.provider.GoogleSheetsConnection" to listOf(""),
      "io.opencui.provider.RestfulConnection" to listOf(""),
      "io.opencui.provider.ScalarContainer" to listOf(""),
      "me.test.foodOrderingProvider.IntWrapper" to listOf(""),
      "me.test.foodOrderingProvider.DishItems" to listOf(""),
      "me.test.foodOrderingProvider.Customization" to listOf(""),
      "me.test.foodOrderingProvider.Options" to listOf(""),
      "me.test.foodOrderingProvider.Orders" to listOf(""),
      "me.test.foodOrderingProvider.OrderItems" to listOf(""),
      "me.test.foodOrderingProvider.DishNameWrapper" to listOf(""),
      "me.test.foodOrderingProvider.SizeWrapper" to listOf(""),
      "me.test.foodOrderingProvider.CustomizationWrapper" to listOf(""),
      "me.test.foodOrderingProvider.OptionWrapper" to listOf(""),
      )
}

public data class Agent(
  public val user: String?,
) : IChatbot() {
  public override val duMeta: DUMeta
    get() = Agent.duMeta

  public override val stateTracker: IStateTracker
    get() = Agent.stateTracker

  public override val rewriteRules: MutableList<KClass<out DialogActRewriter>> = mutableListOf()

  public override val routing: Map<String, RoutingInfo> = mapOf()
  init {
    TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
  }

  public constructor() : this("")

  public companion object {
    public val duMeta: DUMeta = loadDUMetaDsl(ens, Agent::class.java.classLoader, "me.test",
        "foodOrderingApp", "en", "master", "271", "America/Los_Angeles")

    public val stateTracker: IStateTracker = BertStateTracker(duMeta)
  }
}

class DecoderTrackerTest : DuTestHelper() {
    val agent = Agent()
    val stateTracker = DecoderStateTracker(agent.duMeta, "agent")
    val service = stateTracker.nluService
    
    fun testNluService() {
        val frameEvents = service.detectTriggerables(stateTracker.context,"I like to order some food")
        println("frame events: $frameEvents")

        val slots = listOf(
            mapOf("name" to "dishes", "description" to "dishes")
        )
        val values = mapOf(
            "dishes" to listOf("pizza", "apple")
        )

        val results0 = service.fillSlots(stateTracker.context, "I like to order some food", slots, values)
        println("frame events: $results0")

        val results1 = service.fillSlots(stateTracker.context, "I like to order some pizza", slots, values)
        println("frame events: $results1")

        val questions = listOf("Do you need more dish?")
        val utterance = "I like to"
        val results2 = service.yesNoInference(stateTracker.context, utterance, questions)
        println(results2)
    }

    //@Test
    fun testConvert() {
        val expected = ExpectedFrame("io.opencui.core.PagedSelectable", slot="index")
        val frameEvents = stateTracker.convert("s", "I like to order some food", DialogExpectations(expected))
        println("frame events: $frameEvents")

        val frameEvents1 = stateTracker.convert("s", "I like to order some pizza", DialogExpectations())
        println("frame events: $frameEvents1")
    }


    //@Test
    fun testFillSlots() {
        val expected = ExpectedFrame("io.opencui.core.PagedSelectable", slot="index")
        // val frameEvents = stateTracker.convert("s", "the first one", DialogExpectations(expected))
        // println("frame events: $frameEvents")

        val frameEvents1 = stateTracker.convert("s", "1", DialogExpectations(expected))
        println("frame events: $frameEvents1")
    }
}

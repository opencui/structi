package io.opencui.test

import io.opencui.core.*
import io.opencui.core.da.UserDefinedInform

data class HiAction_0(
    val frame: Hi
) : UserDefinedInform<Hi>(frame, simpleTemplates({with(frame) {"""Hi, ${person?.name}, ${person?.height}cm, ${person?.weight}kg I know you are ${person?.age} year old. But how are you?""" }}))

data class HiAction_1(
    val frame: Hi
) : UserDefinedInform<Hi>(frame, simpleTemplates({with(frame) {"""Hi, ${person?.name}, ${person?.height}cm, ${person?.weight}kg I know you are ${person?.age} year old. I am sorry but you are too old!""" }}))

data class PreDiagnosisAction(
    val frame: PreDiagnosis
) : UserDefinedInform<PreDiagnosis>(frame, simpleTemplates({with(frame) {"""Hi, ${person?.name}, I know you have ${indexes?.size} ids. Am I right?""" }}))
data class PreDiagnosisListAction(
    val frame: PreDiagnosis
) : LazyAction(convertDialogActGen({frame.indexes!!}, { table -> UserDefinedInform(frame, simpleTemplates({with(frame) {"""your indices are ${table.joinToString { it.toString() }}""" }})) }))

data class HelloAction(
    val frame: Hello
) : UserDefinedInform<Hello>(frame, simpleTemplates({with(frame) {"""So you want to use ${payable} to pay. Am I right?""" }}))


data class BookFlightAction_0(
        val frame: BookFlight
) : UserDefinedInform<BookFlight>(frame, simpleTemplates({with(frame) {"""Flight ${flight} has been successfully booked for you, depart date is ${depart_date}""" }}))

data class BookHotelAction_0(
        val frame: BookHotel
) : UserDefinedInform<BookHotel>(frame, simpleTemplates({with(frame) {"""Hotel ${hotel?.hotel} has been successfully booked for you, checkin date is ${checkin_date}""" }}))

data class HotelAddressAction_0(val frame: HotelAddress) : UserDefinedInform<HotelAddress>(frame, simpleTemplates({with(frame) {"""Address of ${hotel?.hotel} is ${vacationService.hotel_address(hotel).address}""" }}))

data class MoreBasics_0(
        val frame: MoreBasics
) : UserDefinedInform<MoreBasics>(frame, simpleTemplates({with(frame) {"""original value is ${payMethod?.origValue}, associate slot value is ${associateSlot}""" }}))

data class IntentNeedConfirm_0(val frame: IntentNeedConfirm): UserDefinedInform<IntentNeedConfirm>(frame, simpleTemplates({with(frame) {"""intVal is ${intVar}""" }}))


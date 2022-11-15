package io.opencui.core.confirmation

import io.opencui.core.*
import kotlin.reflect.KMutableProperty0

interface IStatus

data class Yes(
    override var session: UserSession? = null
) : IFrame, IStatus {
    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: Yes? = this@Yes

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}


data class No(
    override var session: UserSession? = null
) : IFrame, IStatus {
    override fun createBuilder(p: KMutableProperty0<out Any?>?): FillBuilder = object : FillBuilder {
        var frame: No? = this@No

        override fun invoke(path: ParamPath): FrameFiller<*> {
            val filler = FrameFiller({ ::frame }, path)
            return filler
        }
    }
}

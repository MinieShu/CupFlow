package com.cupflow.companion

import android.app.Application
import com.rokid.cxr.link.CXRLink

class CupFlowCompanionApplication : Application() {
    var cxrLink: CXRLink? = null
    var token: String = ""
}

package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class NavigationFocusTest {
    private val pkg="com.antutu.ABenchMark"
    private fun dump(window:String="abcd com.antutu.ABenchMark/org.citra.emu.ui.EmulationActivity",app:String=pkg,extra:String="")="""
Input Dispatcher State:
  FocusedDisplayId: 4
  FocusedApplications:
    displayId=4, name='ActivityRecord{aaa u0 dev.thor.agent/.MainActivity} t574}', dispatchingTimeout=60000ms
    displayId=0, name='ActivityRecord{bbb u0 $app/org.citra.emu.ui.EmulationActivity} t575}', dispatchingTimeout=5000ms
  FocusedWindows:
    displayId=4, name='ffff com.antutu.ABenchMark'
    displayId=0, name='$window'
$extra  FocusRequests:
    displayId=0, name='ignored old request'
Input Dispatcher State at time of last ANR:
  FocusedWindows:
    displayId=0, name='1234 unrelated.old/.Activity'
""".trimIndent()
    @Test fun explicitlyRoutedUpperNavigationWorksWithGlobalFocusOnLowerPresentation() {NavigationFocus.verify(dump(),0,pkg)}
    @Test fun upperWindowAndApplicationMustBothMatchExactPackage() {
        for(text in listOf(dump(window="abcd other.app/.Main"),dump(app="other.app"),dump(window="abcd com.antutu.ABenchMark.fake/.Main")))reject(text)
    }
    @Test fun missingOrAmbiguousUpperFocusIsRejected() {
        reject(dump().replace("    displayId=0, name='abcd com.antutu.ABenchMark/org.citra.emu.ui.EmulationActivity'\n",""))
        reject(dump(extra="    displayId=0, name='bbbb com.antutu.ABenchMark/.Other'\n"))
        reject(dump().replace("  FocusedApplications:","  FocusedApplications:\n    displayId=0, name='ActivityRecord{ccc u0 $pkg/.Other} t900}', dispatchingTimeout=5000ms"))
    }
    @Test fun oldAnrAndFocusRequestsCannotSubstituteForCurrentFocus() {
        reject(dump().replace("  FocusedWindows:","  UnknownWindows:").substringBefore("Input Dispatcher State at time of last ANR:"))
        reject(dump().replace("Input Dispatcher State:","Historical State:"))
    }
    private fun reject(text:String) {try {NavigationFocus.verify(text,0,pkg);fail("Invalid navigation focus accepted")}catch(e:ToolFailure){assertEquals(Code.TARGET_CHANGED,e.code)}}
}

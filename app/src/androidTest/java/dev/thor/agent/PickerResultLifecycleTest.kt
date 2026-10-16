package dev.thor.agent

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** The platform result callback intentionally runs before onCreate/bind/attach.
 * No Activity is launched, Service started, database opened or URI read. */
@RunWith(AndroidJUnit4::class)
class PickerResultLifecycleTest {
    private fun field(name:String)=MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}
    private fun result(activity:MainActivity,code:Int,status:Int,uri:String?) {
        MainActivity::class.java.getDeclaredMethod("onActivityResult",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType,Intent::class.java)
            .apply {isAccessible=true}.invoke(activity,code,status,uri?.let {Intent().setData(Uri.parse(it))})
    }
    private fun consume(activity:MainActivity)=MainActivity::class.java.getDeclaredMethod("consumePendingPickerResult").apply {isAccessible=true}.invoke(activity)

    @Test fun imageAndSkillResultsBeforeServiceAttachStayPendingWithoutTouchingHost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for(code in listOf(71,72)) {
                val activity=MainActivity()
                field("pickerSession").set(activity,"original-session")
                val uri="content://offline-fixture/document/$code"
                result(activity,code,Activity.RESULT_OK,uri)
                val pending=field("pendingPickerResult").get(activity) as PendingPickerResult
                assertEquals(PendingPickerResult(code,uri,"original-session"),pending)
                assertEquals("original-session",field("pickerSession").get(activity))
                assertNull("Result callback must not create or access a host",field("host").get(activity))
                // Repeated readiness attempts before attach retain the result;
                // they never import through an old Activity's host alias.
                repeat(2){consume(activity)}
                assertSame(pending,field("pendingPickerResult").get(activity))
                assertNull(field("attachedEngine").get(activity))
                assertNull(field("runtimeService").get(activity))
            }
        }
    }

    @Test fun pendingResultSurvivesSavedStateWithItsOriginalSessionAndUri() {
        for(code in listOf(71,72)) {
            val before=PendingPickerResult(code,"content://offline-fixture/document/$code","selected-before-picker")
            val saved=Bundle();before.save(saved)
            val restored=PendingPickerResult.restore(Bundle(saved))
            assertEquals(before,restored)
            assertEquals("selected-before-picker",restored!!.sessionId)
        }
        assertNull(PendingPickerResult.restore(null))
        assertNull(PendingPickerResult.restore(Bundle().apply {putInt("pickerResult.requestCode",71);putString("pickerResult.uri","content://offline-fixture/no-session")}))
    }

    @Test fun cancellationOrMissingImageSessionCannotAccessUninitializedHost() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            for(code in listOf(71,72)) {
                val activity=MainActivity()
                field("pickerSession").set(activity,"original-session")
                result(activity,code,Activity.RESULT_OK,"content://offline-fixture/pending")
                result(activity,code,Activity.RESULT_CANCELED,null)
                assertNull(field("pendingPickerResult").get(activity))
                assertNull(field("pickerSession").get(activity))
                assertNull(field("host").get(activity))
            }
            val noSession=MainActivity()
            result(noSession,71,Activity.RESULT_OK,"content://offline-fixture/image")
            assertNull(field("pendingPickerResult").get(noSession))
            assertNull(field("host").get(noSession))
        }
    }
}

package dev.thor.inputlab

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent

/** Parser qualification only: constructed samples are never dispatched or journalled as OS evidence. */
class SnapshotInstrumentation : Instrumentation() {
    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); start() }
    override fun onStart() {
        val result=Bundle()
        try {
            val e=MotionEvent.obtain(10,10,MotionEvent.ACTION_MOVE,3f,4f,1f,1f,0,1f,1f,7,0)
            e.source=Sources.TOUCHSCREEN
            e.addBatch(20,8f,9f,0.5f,0.6f,0)
            val (raw,body)=Capture.motion(e,"touch")
            e.recycle()
            check(raw.samples.size==2 && raw.samples[0].time==10L && raw.samples[1].time==20L)
            check(raw.samples[0].x==3f && raw.samples[1].x==8f)
            check(body["historySize"]==1 && body["eventDisplayId"]==null)
            check(Json.encode(body).contains("historical_raw_coordinates_not_exposed"))

            val props=Array(2) { i -> MotionEvent.PointerProperties().apply { id=4+i; toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coords=Array(2) { i -> MotionEvent.PointerCoords().apply { x=(10+i).toFloat(); y=30f; pressure=0.7f; size=0.3f } }
            val multi=MotionEvent.obtain(1,2,MotionEvent.ACTION_MOVE,2,props,coords,0,0,1f,1f,5,0,Sources.TOUCHSCREEN,0)
            multi.addBatch(3,coords,0)
            val (_,multiBody)=Capture.motion(multi,"touch"); multi.recycle()
            val json=Json.encode(multiBody)
            check(multiBody["pointerCount"]==2 && multiBody["historySize"]==1)
            check(json.contains("\"pointerId\":4") && json.contains("\"pointerId\":5"))

            val joy=MotionEvent.obtain(1,2,MotionEvent.ACTION_MOVE,1, arrayOf(MotionEvent.PointerProperties().apply { id=0 }),
                arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(17,0.9f); setAxisValue(0,0.8f) }),0,0,1f,1f,9,0,Sources.JOYSTICK,0)
            joy.addBatch(3,arrayOf(MotionEvent.PointerCoords().apply { setAxisValue(17,0f); setAxisValue(0,0f) }),0)
            val (joystick,_)=Capture.motion(joy,"generic"); joy.recycle()
            check(joystick.samples[0].axes[17]==0.9f && joystick.samples[1].axes[17]==0f)

            val key=KeyEvent(1,2,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_BUTTON_A,2,0,3,19,0,Sources.GAMEPAD or 0x100)
            val (k,keyBody)=Capture.key(key)
            check(k.repeat==2 && Sources.controller(k.source) && keyBody["scanCode"]==19)
            result.putString("stream","4 Android parser fixture checks passed. recordOrigin=fixture_replay; no system injection or Activity dispatch performed.")
            result.putInt("fixtureChecksPassed",4)
            finish(Activity.RESULT_OK,result)
        } catch(e: Throwable) {
            result.putString("stream",e.stackTraceToString()); finish(Activity.RESULT_CANCELED,result)
        }
    }
}

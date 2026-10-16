package dev.thor.agent.testinput;

import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.MotionEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;

/** Test APK only. Shell app_process produces real display-specific platform
 * touchscreen events; the instrumentation drives stages through stdin. */
public final class VoiceGestureInjector {
    private Object input;
    private Method inject;
    private Method display;
    private int displayId;
    private long downTime;
    private boolean held;
    private float x,y;
    private void event(int action,float nextX,float nextY) throws Exception {
        MotionEvent.PointerProperties prop=new MotionEvent.PointerProperties();prop.id=0;prop.toolType=MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords coord=new MotionEvent.PointerCoords();coord.x=nextX;coord.y=nextY;coord.pressure=action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL?0:1;coord.size=1;
        MotionEvent e=MotionEvent.obtain(downTime,SystemClock.uptimeMillis(),action,1,new MotionEvent.PointerProperties[]{prop},new MotionEvent.PointerCoords[]{coord},0,0,1,1,0,0,InputDevice.SOURCE_TOUCHSCREEN,0);
        try {display.invoke(e,displayId);if(!Boolean.TRUE.equals(inject.invoke(input,e,2)))throw new IllegalStateException("System rejected platform event");}
        finally {e.recycle();}
        x=nextX;y=nextY;
    }
    // Hidden APIs execute only in this separate adb-shell UID 2000 process,
    // never in the application UID or production APK; enforce that before lookup.
    @android.annotation.SuppressLint({"BlockedPrivateApi", "PrivateApi"})
    private void run(String[] args) throws Exception {
        if(Process.myUid()!=2000)throw new SecurityException("This test helper requires adb shell UID 2000");
        if(args.length!=1)throw new IllegalArgumentException("One explicit display ID required");
        displayId=Integer.parseInt(args[0]);if(displayId<0)throw new IllegalArgumentException("Invalid display");
        if(Looper.getMainLooper()==null)Looper.prepareMainLooper();
        Class<?> manager=Class.forName("android.hardware.input.InputManager");input=manager.getMethod("getInstance").invoke(null);inject=manager.getMethod("injectInputEvent",InputEvent.class,int.class);display=MotionEvent.class.getMethod("setDisplayId",int.class);
        System.out.println("READY uid=2000 display="+displayId);System.out.flush();
        try(BufferedReader commands=new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while((line=commands.readLine())!=null) {
                String[] values=line.trim().split("\\s+");String op=values[0];
                if(op.equals("QUIT"))break;
                if(values.length!=3)throw new IllegalArgumentException("Expected ACTION X Y");
                float nextX=Float.parseFloat(values[1]),nextY=Float.parseFloat(values[2]);
                if(!Float.isFinite(nextX)||!Float.isFinite(nextY)||nextX<0||nextY<0||nextX>16384||nextY>16384)throw new IllegalArgumentException("Coordinates out of bounds");
                switch(op) {
                    case "DOWN":if(held)throw new IllegalStateException("Already down");downTime=SystemClock.uptimeMillis();held=true;event(MotionEvent.ACTION_DOWN,nextX,nextY);break;
                    case "MOVE":if(!held)throw new IllegalStateException("No active touch");float oldX=x,oldY=y;for(int i=1;i<=8;i++){event(MotionEvent.ACTION_MOVE,oldX+(nextX-oldX)*i/8,oldY+(nextY-oldY)*i/8);SystemClock.sleep(16);}break;
                    case "UP":if(!held)throw new IllegalStateException("No active touch");event(MotionEvent.ACTION_UP,nextX,nextY);held=false;break;
                    default:throw new IllegalArgumentException("Unknown action");
                }
                System.out.println("ACK "+op+" downTime="+downTime+" eventTime="+SystemClock.uptimeMillis()+" display="+displayId);System.out.flush();
            }
        } finally {if(held){event(MotionEvent.ACTION_CANCEL,x,y);held=false;}}
    }
    public static void main(String[] args) throws Exception {new VoiceGestureInjector().run(args);}
}

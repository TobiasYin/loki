package dev.thor.agent

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.*

/** Foreground only for user-started ongoing Agent work. Not sticky: process death
 * recovers durable interrupted history when opened, never replays a task. */
class AgentRuntimeService:Service() {
    companion object {
        const val ACTION_RUN="com.tobias.loki.runtime.RUN"
        const val ACTION_STOP="com.tobias.loki.runtime.STOP"
        const val CHANNEL="agent-running"
        const val NOTIFICATION_ID=401
    }
    inner class LocalBinder:Binder() {val service get()=this@AgentRuntimeService}
    private val binder=LocalBinder()
    private val main=Handler(Looper.getMainLooper())
    internal var engine:AgentRuntimeEngine?=null;private set
    internal var foreground=false;private set
    private var bound=false
    private var activeRuns=0
    private var pendingBegins=0
    private val workHolds=mutableSetOf<String>()
    private var destroying=false
    private var reloading=false
    private val engineReadyCallbacks=mutableListOf<()->Unit>()
    internal var initializationError:String?=null;private set
    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Agent 后台任务",NotificationManager.IMPORTANCE_LOW).apply {description="用户发起的 Agent 正在运行，可随时停止"})
        createEngine()
    }
    private fun createEngine() {
        try {engine=AgentRuntimeEngine(this);initializationError=null}
        catch(error:Exception) {initializationError="本地执行环境初始化失败；历史保留，未重放任务。";if(BuildConfig.DEBUG)android.util.Log.e("ThorInit",error.javaClass.simpleName)}
    }
    override fun onBind(intent:Intent):IBinder {bound=true;return binder}
    override fun onRebind(intent:Intent) {bound=true;super.onRebind(intent)}
    override fun onUnbind(intent:Intent):Boolean {bound=false;if(activeRuns==0 && pendingBegins==0 && workHolds.isEmpty())stopSelf();return true}
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        if(intent?.action==ACTION_STOP)engine?.stopAll()
        else if(intent?.action==ACTION_RUN){promote();settleForeground()}
        if(activeRuns==0 && pendingBegins==0 && workHolds.isEmpty() && !bound){stopForeground(STOP_FOREGROUND_REMOVE);foreground=false;stopSelf(startId)}
        return START_NOT_STICKY
    }
    private fun ensureForeground() {
        check(Looper.myLooper()==Looper.getMainLooper() && !destroying)
        if(!foreground) {
            startForegroundService(Intent(this,AgentRuntimeService::class.java).setAction(ACTION_RUN))
            promote() // Before Native begin admits network/device effects.
        }
    }
    internal fun ensureForegroundForRun() {ensureForeground();pendingBegins++}
    /** One user send may contain a configuration probe and its actual Run. Keep
     * foreground eligibility across that handoff; these IDs grant no authority. */
    internal fun beginWork(id:String) {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,100}")) && id !in workHolds && workHolds.size<16)
        ensureForeground();workHolds.add(id)
    }
    internal fun endWork(id:String) {workHolds.remove(id);settleForeground()}
    internal fun connectionInvalidated() {workHolds.clear();pendingBegins=0;settleForeground()}

    internal fun beginResolved(started:Boolean) {
        if(pendingBegins>0)pendingBegins--
        if(!started)settleForeground()
    }
    /** Safe for ProductHost's serial-executor callbacks. */
    fun updateActiveRuns(count:Int) {
        if(Looper.myLooper()!=Looper.getMainLooper()){main.post {updateActiveRuns(count)};return}
        if(destroying)return
        activeRuns=count.coerceAtLeast(0)
        if(activeRuns>0 && foreground)getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID,notification())
        else settleForeground()
    }
    private fun settleForeground() {
        if(activeRuns==0 && pendingBegins==0 && workHolds.isEmpty() && foreground) {
            stopForeground(STOP_FOREGROUND_REMOVE);foreground=false
            if(!bound)stopSelf()
        }
    }
    private fun promote() {
        val notification=notification()
        if(Build.VERSION.SDK_INT>=34)startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTIFICATION_ID,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        foreground=true
    }
    private fun notification():Notification {
        val open=PendingIntent.getActivity(this,401,Intent(this,LauncherActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val stop=PendingIntent.getService(this,402,Intent(this,AgentRuntimeService::class.java).setAction(ACTION_STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val builder=Notification.Builder(this,CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Loki 正在运行")
            .setContentText(if(activeRuns>0)"正在执行 $activeRuns 个任务，可返回聊天查看或停止" else "正在准备你发起的任务")
            .setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_SERVICE)
            .addAction(Notification.Action.Builder(null,"停止所有任务",stop).build())
        if(Build.VERSION.SDK_INT>=31)builder.setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
        return builder.build()
    }
    internal fun rendererFailed() {activeRuns=0;pendingBegins=0;workHolds.clear();settleForeground()}
    internal fun whenEngineReady(callback:()->Unit) {
        if(reloading)engineReadyCallbacks.add(callback) else callback()
    }
    internal fun reloadConfiguration(onReady:()->Unit={}):Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(destroying || reloading || activeRuns>0 || pendingBegins>0 || workHolds.isNotEmpty() || engine?.host?.hasActiveRuns()==true)return false
        reloading=true
        val previous=engine;engine=null;previous?.close()
        val closed=previous?.closeCompletion ?: java.util.concurrent.CompletableFuture.completedFuture(Unit)
        closed.whenComplete {_,_->main.post {
            if(!destroying) {
                createEngine();reloading=false;onReady()
                val callbacks=engineReadyCallbacks.toList();engineReadyCallbacks.clear();callbacks.forEach {it()}
            }
        }}
        return true
    }
    override fun onDestroy() {
        destroying=true;workHolds.clear();engineReadyCallbacks.clear();engine?.close();engine=null;main.removeCallbacksAndMessages(null)
        stopForeground(STOP_FOREGROUND_REMOVE);foreground=false
        super.onDestroy()
    }
}

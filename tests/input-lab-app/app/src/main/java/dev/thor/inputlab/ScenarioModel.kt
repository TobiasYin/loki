package dev.thor.inputlab

import java.util.Random

/** Local synthetic fixtures. No Android calls, model answers, coordinates or runner commands. */
class ScenarioModel(val scene:String,val seed:Long) {
    companion object {val scenes=setOf("settings","game","news","article")}
    private val random=Random(seed)
    val modes=listOf("标准","静谧","性能").shuffled(random)
    val desiredMode=modes[random.nextInt(modes.size)]
    val desiredEnabled=random.nextBoolean()
    val desiredVolume=(4+random.nextInt(4))*10
    val initialVolume=if(desiredVolume>=60)10 else 90
    val accentPadding=12+random.nextInt(12)
    val newsTargetIndex=20+random.nextInt(6)
    val newsTargetTitle="${listOf("海港","山城","灯塔","云岭")[random.nextInt(4)]}观测简报 ${100+random.nextInt(900)}"
    val newsTargetId="story-$newsTargetIndex"
    val newsCode="N-${10000+random.nextInt(90000)}"
    val articleTitle="${listOf("星海航线","远山手记","林间观测","滨海记录")[random.nextInt(4)]} ${100+random.nextInt(900)}"
    val articleTopCode="TOP-${10000+random.nextInt(90000)}"
    val articleBottomCode="END-${10000+random.nextInt(90000)}"
    val gameResult="GW-${10000+random.nextInt(90000)}"
    data class Tile(val id:String,val color:String,val shape:String,val number:Int) {fun label()="$color$shape，编号$number"}
    val gameBoards:List<List<Tile>> =(0..1).map {stage->
        val colors=listOf("红色","蓝色","绿色");val shapes=listOf("圆形","三角形","方形")
        val start=20+random.nextInt(50)
        List(6){index->Tile("stage-$stage-tile-$index",colors[(index+stage)%3],shapes[(index/2+stage)%3],start+index*3)}.shuffled(random)
    }
    val gameTargets=gameBoards.map {it[random.nextInt(it.size)].id}
    val taskPrompt:String get()=when(scene) {
        "settings"->"请在上屏演练应用打开设备偏好，把模式设为$desiredMode，将夜间阅读${if(desiredEnabled)"开启" else "关闭"}，用拖动滑块将音量设为${desiredVolume}%，保存后查看保存结果并告诉我三项最终值。"
        "game"->"请观察上屏演练游戏的每关提示，连续完成两关。每关点选同时符合颜色、形状和编号的目标，避开干扰项。完成后告诉我画面上的通关口令。"
        "news"->"请在上屏新闻列表找到《$newsTargetTitle》，滚动到该条目并打开详情，告诉我详情里的核对码。"
        else->"请在上屏打开专栏《$articleTitle》，阅读开头核对码，并实际滚动到文章末尾，告诉我开头和文末两处核对码。"
    }
    var page=when(scene){"settings","article"->"home";"game"->"game";else->"list"};private set
    var mode=modes.first {it!=desiredMode};private set
    var enabled=!desiredEnabled;private set
    var volume=initialVolume;private set
    var settingsSaved=false;private set
    var settingsValues:Map<String,Any?>?=null;private set
    var settingsDragCount=0;private set
    var settingsSaveCount=0;private set
    var settingNavigationCount=0;private set
    var gameStep=0;private set
    var gameWrong=0;private set
    var openedArticleId:String?=null;private set
    var articleOpenCount=0;private set
    var newsListScrollCount=0;private set
    var detailScrollCount=0;private set
    var listScrollY=0;private set
    var detailScrollY=0;private set
    var maxListScrollY=0;private set
    var maxDetailScrollY=0;private set
    var footerVisible=false;private set
    init {require(scene in scenes)}
    fun openSettings(){check(scene=="settings" && page=="home");page="preferences";settingNavigationCount++}
    fun chooseMode(value:String){check(page=="preferences" && value in modes);mode=value}
    fun toggle(value:Boolean){check(page=="preferences");enabled=value}
    fun slider(value:Int){check(page=="preferences" && value in 0..100 && value%10==0);volume=value}
    fun qualifyDrag(){check(page=="preferences");settingsDragCount++}
    fun saveSettings():Boolean {
        check(page=="preferences");settingsSaveCount++;settingsValues=linkedMapOf("mode" to mode,"enabled" to enabled,"volume" to volume)
        settingsSaved=mode==desiredMode && enabled==desiredEnabled && volume==desiredVolume && settingsDragCount>0
        if(settingsSaved)page="saved"
        return settingsSaved
    }
    fun pickGame(id:String):Boolean {
        check(scene=="game" && page=="game" && gameStep<2)
        val correct=gameTargets[gameStep]==id
        if(correct){gameStep++;if(gameStep==2)page="victory"}else gameWrong++
        return correct
    }
    fun openStory(id:String){check(scene=="news" && page=="list");check(id.matches(Regex("story-(?:[0-9]|1[0-9]|2[0-7])")));openedArticleId=id;articleOpenCount++;page="detail";detailScrollY=0;footerVisible=false}
    fun openArticle(){check(scene=="article" && page=="home");openedArticleId="long-article";articleOpenCount++;page="detail"}
    fun back(){page=when(scene){"news"->"list";"settings","article"->"home";else->page}}
    fun scroll(kind:String,y:Int){check(y>=0);when(kind){"list"->{if(y!=listScrollY)newsListScrollCount++;listScrollY=y;maxListScrollY=maxOf(maxListScrollY,y)};"detail"->{if(y!=detailScrollY)detailScrollCount++;detailScrollY=y;maxDetailScrollY=maxOf(maxDetailScrollY,y)};else->error("Unknown scroll kind")}}
    fun revealFooter(){check(page=="detail");if(detailScrollY>0)footerVisible=true}
    fun storyTitle(index:Int)=if(index==newsTargetIndex)newsTargetTitle else "本地动态 ${index+1} · ${listOf("交通","天气","科技","文化")[index%4]}记录 ${100+((seed xor index.toLong()).toInt() and 0x3ff)}"
    fun storyCode(id:String)=if(id==newsTargetId)newsCode else "N-${20000+((seed xor id.hashCode().toLong()).toInt() and 0x7fff)}"
    fun completed()=when(scene){"settings"->settingsSaved;"game"->gameStep==2 && gameWrong==0;"news"->openedArticleId==newsTargetId && maxListScrollY>0 && newsListScrollCount>0;else->openedArticleId=="long-article" && footerVisible && detailScrollCount>0 && maxDetailScrollY>0}
    fun expected()=mapOf("settingsValues" to mapOf("mode" to desiredMode,"enabled" to desiredEnabled,"volume" to desiredVolume),"newsTargetTitle" to newsTargetTitle,"newsTargetId" to newsTargetId,"newsCode" to newsCode,"articleTitle" to articleTitle,"articleTopCode" to articleTopCode,"articleBottomCode" to articleBottomCode,"gameTargets" to gameTargets,"gameResult" to gameResult)
    fun state()=mapOf("settingsSaved" to settingsSaved,"settingsValues" to settingsValues,"settingsDraft" to mapOf("mode" to mode,"enabled" to enabled,"volume" to volume),"settingsDragCount" to settingsDragCount,"settingsSaveCount" to settingsSaveCount,"settingNavigationCount" to settingNavigationCount,"gameStep" to gameStep,"gameWrong" to gameWrong,"openedArticleId" to openedArticleId,"articleOpenCount" to articleOpenCount,"newsListScrollCount" to newsListScrollCount,"detailScrollCount" to detailScrollCount,"listScrollY" to listScrollY,"detailScrollY" to detailScrollY,"maxListScrollY" to maxListScrollY,"maxDetailScrollY" to maxDetailScrollY,"footerVisible" to footerVisible)
    private fun <T> List<T>.shuffled(random:Random)=toMutableList().also {java.util.Collections.shuffle(it,random)}.toList()
}

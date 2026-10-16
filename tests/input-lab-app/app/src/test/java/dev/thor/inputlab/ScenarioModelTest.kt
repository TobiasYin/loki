package dev.thor.inputlab

import org.junit.Assert.*
import org.junit.Test

class ScenarioModelTest {
    @Test fun seedIsDeterministicAndVariesTargetsOrderAndValues() {
        val a=ScenarioModel("game",71);val b=ScenarioModel("game",71)
        assertEquals(a.expected(),b.expected());assertEquals(a.gameBoards,b.gameBoards)
        val cases=(1L..30L).map {ScenarioModel("settings",it)}
        assertTrue(cases.map {it.desiredVolume}.distinct().size>2)
        assertTrue(cases.map {it.modes}.distinct().size>2)
        assertTrue(cases.map {it.newsTargetTitle}.distinct().size>20)
        assertTrue(cases.all {it.newsTargetIndex>=20})
        assertFalse(a.taskPrompt.contains(a.gameResult));assertFalse(ScenarioModel("news",71).taskPrompt.contains(a.newsCode))
    }
    @Test fun settingsNeedsNavigationCorrectValuesRealDragQualificationAndSave() {
        val m=ScenarioModel("settings",930)
        assertFalse(m.completed());assertThrows(IllegalStateException::class.java){m.slider(40)}
        m.openSettings();m.chooseMode(m.desiredMode);m.toggle(m.desiredEnabled);m.slider(m.desiredVolume)
        assertFalse("A click/assigned progress without a drag is insufficient",m.saveSettings());assertFalse(m.completed())
        m.qualifyDrag();assertTrue(m.saveSettings());assertTrue(m.completed());assertEquals(m.expected()["settingsValues"],m.settingsValues)
        assertEquals(1,m.settingNavigationCount);assertEquals(2,m.settingsSaveCount);assertEquals(1,m.settingsDragCount)
    }
    @Test fun wrongGameTargetCannotQualifyEvenAfterLaterCorrectPicks() {
        val m=ScenarioModel("game",42)
        assertFalse(m.pickGame(m.gameBoards[0].first {it.id!=m.gameTargets[0]}.id));assertEquals(0,m.gameStep)
        m.gameTargets.forEach {assertTrue(m.pickGame(it))};assertEquals("victory",m.page);assertFalse(m.completed());assertEquals(1,m.gameWrong)
        val clean=ScenarioModel("game",42);clean.gameTargets.forEach {clean.pickGame(it)};assertTrue(clean.completed())
    }
    @Test fun newsAndArticleRequireRealScrollAndCorrectDetailNotOffscreenEnumeration() {
        val news=ScenarioModel("news",909);news.openStory(news.newsTargetId);assertFalse(news.completed())
        news.back();news.scroll("list",600);news.openStory(news.newsTargetId);assertTrue(news.completed());assertEquals(2,news.articleOpenCount)
        val article=ScenarioModel("article",811);article.openArticle();article.revealFooter();assertFalse(article.completed())
        article.scroll("detail",1700);assertFalse(article.completed());article.revealFooter();assertTrue(article.completed())
        assertEquals(1700,article.maxDetailScrollY);assertTrue(article.footerVisible)
    }
    @Test fun unsupportedSceneAndInvalidActionsRejectWithoutForgingCompletion() {
        assertThrows(IllegalArgumentException::class.java){ScenarioModel("arbitrary",1)}
        val m=ScenarioModel("settings",1);m.openSettings();assertThrows(IllegalStateException::class.java){m.slider(44)}
        assertFalse(m.completed())
    }
}

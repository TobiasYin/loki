package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test

class AccessibilityRecoveryNoticeTest {
    private class Time:Clock {var time=0L;override fun now()=time}
    @Test fun initiallyUnconfiguredServiceDoesNotNag() {
        val time=Time();val notice=AccessibilityRecoveryNotice(time)
        assertFalse(notice.update(false,false,false))
        time.time=60000;assertFalse(notice.update(false,false,false))
    }
    @Test fun savedEnabledButDisconnectedServiceNeedsOneManualReminderAfterGrace() {
        val time=Time();val notice=AccessibilityRecoveryNotice(time)
        assertFalse(notice.update(false,false,true))
        time.time=2999;assertFalse(notice.update(false,false,true))
        time.time=3000;assertTrue(notice.update(false,false,true))
        time.time=60000;assertFalse(notice.update(false,false,true))
    }
    @Test fun automaticRecoverySuppressesReminderAndBackendLossStartsNewGrace() {
        val time=Time();val notice=AccessibilityRecoveryNotice(time)
        notice.update(true,false,true)
        notice.update(false,false,true)
        time.time=2000;assertFalse(notice.update(false,true,true))
        time.time=60000;assertFalse(notice.update(false,true,true))
        assertFalse(notice.update(false,false,true))
        time.time+=3000;assertTrue(notice.update(false,false,true))
    }
    @Test fun recoveredConnectionResetsReminderAndLaterLossCanBeReportedEvenIfSettingWasCleared() {
        val time=Time();val notice=AccessibilityRecoveryNotice(time)
        notice.update(true,false,true);notice.update(false,false,false)
        time.time=3000;assertTrue(notice.update(false,false,false))
        notice.update(true,false,true);notice.update(false,false,true)
        time.time=6000;assertTrue(notice.update(false,false,true))
    }
}

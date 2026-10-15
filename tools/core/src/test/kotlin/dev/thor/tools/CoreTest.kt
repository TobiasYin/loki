package dev.thor.tools

import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.spec.SecretKeySpec

class CoreTest {
    private class Time(var time: Long = 1000) : Clock { override fun now() = time }
    private fun expect(code: Code, block: () -> Unit) {
        try { block(); fail("Expected $code") } catch (f: ToolFailure) { assertEquals(code, f.code) }
    }
    private val upper = DisplayFact(7, "test-upper", 800, 600, 0, 240)
    private val lower = DisplayFact(9, "test-lower", 640, 480, 0, 240)
    private class FakeBackend(val action: (Envelope, Checkpoint) -> Unit = { _, _ -> }) : DeviceBackend {
        var executions = 0
        override fun availability(toolId: String) = Availability(Status.READY, "TEST fake only", "fake")
        override fun execute(envelope: Envelope, args: Map<String, Any?>, checkpoint: Checkpoint): ToolResult {
            executions++; action(envelope, checkpoint)
            return ToolResult(1000, "fake", availability(envelope.toolId), mapOf("secret" to "test"))
        }
        override fun release(runId: String) = "test release"
    }
    @Test fun boundedNetworkWorkDoesNotOwnTheDeviceCommitLock() {
        val time=Time();val started=CountDownLatch(1);val finish=CountDownLatch(1)
        val backend=FakeBackend {e,cp->if(e.toolId=="files.download"){started.countDown();assertTrue(finish.await(3,TimeUnit.SECONDS));cp.check()}}
        val authority=ManualTestAuthority(time);val lease=authority.begin(backend)
        val gateway=Gateway(authority,backend,Snapshots(time){Target("app","valid","TEST")},time)
        fun envelope(tool:String)=Envelope(lease.session,lease.run,tool,tool,lease.epoch,lease.deadline)
        val task=java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            val network=task.submit<ToolResult> {gateway.execute(envelope("files.download"),emptyMap())}
            assertTrue(started.await(2,TimeUnit.SECONDS))
            assertNull(gateway.execute(envelope("perception.device_info"),emptyMap()).error)
            finish.countDown();assertNull(network.get(2,TimeUnit.SECONDS).error)
        } finally {finish.countDown();task.shutdownNow()}
    }
    @Test fun defaultsAndEffectiveSetAreLeastPrivilege() {
        val authority = ManualTestAuthority(Time()); val lease = authority.begin(FakeBackend())
        assertTrue(authority.enabled(Capability.SCREEN_PERCEPTION)); assertTrue(authority.enabled(Capability.WEB_SEARCH))
        assertFalse(authority.enabled(Capability.SCREEN_CONTROL)); assertFalse(authority.enabled(Capability.ADB_DEBUG))
        assertEquals(ToolPack.descriptors.values.filter { it.capability in setOf(Capability.SCREEN_PERCEPTION,Capability.WEB_SEARCH) }.map { it.toolId }.toSet(), lease.tools)
    }
    @Test fun crossSessionUnknownToolSchemaAndDeadlineAreRejected() {
        val time = Time(); val authority = ManualTestAuthority(time); val lease = authority.begin(FakeBackend())
        val e = Envelope(lease.session, lease.run, "call", "perception.device_info", lease.epoch, lease.deadline)
        expect(Code.STALE_AUTHORIZATION) { authority.check(e.copy(sessionId = "other")) }
        expect(Code.STALE_AUTHORIZATION) { authority.check(e.copy(runId = "other")) }
        expect(Code.UNSUPPORTED) { authority.check(e.copy(toolId = "unknown")) }
        expect(Code.UNSUPPORTED) { authority.check(e.copy(schemaVersion = 2)) }
        expect(Code.TIMEOUT) { authority.check(e.copy(deadline = lease.deadline + 1)) }
        time.time = lease.deadline
        expect(Code.TIMEOUT) { authority.check(e) }
    }
    @Test fun queuedAuthorizationNeverRevivesAfterOffOn() {
        val authority = ManualTestAuthority(Time()); val old = authority.begin(FakeBackend())
        authority.set(Capability.SCREEN_PERCEPTION, false); authority.set(Capability.SCREEN_PERCEPTION, true)
        val next = authority.begin(FakeBackend())
        expect(Code.STALE_AUTHORIZATION) { authority.check(Envelope(next.session, next.run, "c", "perception.device_info", old.epoch, next.deadline)) }
        assertTrue(old.cancel.cancelled)
    }
    @Test fun capabilityDisabledHasExplicitError() {
        val authority = ManualTestAuthority(Time()); val l = authority.begin(FakeBackend())
        expect(Code.CAPABILITY_DISABLED) { authority.check(Envelope(l.session, l.run, "c", "debug.shell", l.epoch, l.deadline)) }
    }
    @Test fun gatewayDiscardsLateReadAfterRevocation() {
        val time = Time(); val authority = ManualTestAuthority(time)
        val backend = FakeBackend { _, _ -> authority.set(Capability.SCREEN_PERCEPTION, false) }
        val l = authority.begin(backend)
        val result = Gateway(authority, backend, Snapshots(time) { Target(null, "unknown", "test") }, time)
            .execute(Envelope(l.session, l.run, "c", "perception.device_info", l.epoch, l.deadline), emptyMap())
        assertEquals(Code.CANCELLED, result.error); assertTrue(result.data.isEmpty()); assertEquals("not_submitted", result.outcome)
    }
    @Test fun submittedCancellationIsUnknownAndInvokesOwnedCleanup() {
        val time = Time(); val authority = ManualTestAuthority(time); authority.set(Capability.ADB_DEBUG, true)
        var released = false
        val backend = FakeBackend { _, cp -> cp.cancellation.register { released = true }; cp.submitted(); authority.stop() }
        val l = authority.begin(backend)
        val r = Gateway(authority, backend, Snapshots(time) { Target(null, "unknown", "test") }, time)
            .execute(Envelope(l.session, l.run, "c", "debug.shell", l.epoch, l.deadline), emptyMap())
        assertEquals(Code.CANCELLED, r.error); assertEquals("unknown", r.outcome); assertTrue(released)
    }
    @Test fun cancellationRegistrationAfterStopStillRuns() {
        val c = Cancellation(); c.cancel(); var count = 0; c.register { count++ }; c.cancel()
        assertEquals(1, count); expect(Code.CANCELLED) { c.check() }
    }
    @Test fun inputEffectMayChangeItsOwnLayoutAfterSubmission() {
        val time=Time();val a=ManualTestAuthority(time);a.set(Capability.SCREEN_CONTROL,true)
        var target=Target("game","valid","v1");val snaps=Snapshots(time) { target };snaps.update(listOf(upper,lower));snaps.calibrate(7,9);val s=snaps.capture()
        val backend=FakeBackend { _,cp -> cp.effect { target=target.copy(revision="own-tap-changed-layout") } }
        val l=a.begin(backend);val r=Gateway(a,backend,snaps,time).execute(Envelope(l.session,l.run,"c","input.tap",l.epoch,l.deadline,s.id),emptyMap())
        assertNull(r.error);assertEquals(1,backend.executions)
    }
    @Test fun requestSpecificBackendPermissionIsCheckedAndReportedBeforeDispatch() {
        val time=Time();val a=ManualTestAuthority(time);a.set(Capability.ADB_DEBUG,true)
        val fake=FakeBackend()
        val backend=object:DeviceBackend by fake {
            override fun availability(toolId:String,args:Map<String,Any?>)=
                if(args["backend"]=="shizuku")Availability(Status.PERMISSION_MISSING,"TEST Shizuku missing","fake-requested-shizuku") else fake.availability(toolId)
        }
        val l=a.begin(backend)
        val result=Gateway(a,backend,Snapshots(time) { Target(null,"unknown","test") },time).execute(
            Envelope(l.session,l.run,"c","files.read",l.epoch,l.deadline),mapOf("backend" to "shizuku"))
        assertEquals(Code.PERMISSION_MISSING,result.error);assertEquals("fake-requested-shizuku",result.source);assertEquals(0,fake.executions)
    }
    @Test fun finalAtomicEffectGuardRejectsRevokedQueuedCommit() {
        val time=Time();val a=ManualTestAuthority(time);val backend=FakeBackend();val l=a.begin(backend)
        val e=Envelope(l.session,l.run,"c","perception.device_info",l.epoch,l.deadline)
        var committed=false
        val cp=Checkpoint({a.validate(e)},l.cancel) { effect -> a.commit(e,effect) }
        a.stop()
        expect(Code.CANCELLED) { cp.effect { committed=true } }
        assertFalse(committed);assertFalse(cp.submitted)
    }
    @Test fun replayAndRunToolBudgetAreRejected() {
        val a=ManualTestAuthority(Time());val b=FakeBackend();val l=a.begin(b)
        val e=Envelope(l.session,l.run,"c","perception.device_info",l.epoch,l.deadline)
        a.claim(e)
        expect(Code.STALE_AUTHORIZATION) { a.claim(e) }
        repeat(31) { a.claim(e.copy(requestId="request-$it",toolCallId="call-$it")) }
        expect(Code.LIMIT_EXCEEDED) { a.claim(e.copy(requestId="overflow",toolCallId="overflow")) }
    }
    @Test fun detachedShellChildCannotHoldOutputDrainForever() {
        val clock=Clock { System.nanoTime()/1_000_000 };val shell=BoundedShell("/bin/sh",clock)
        val start=clock.now();val r=shell.execute("sleep 1 & printf done",500,32,Cancellation()) {}
        assertTrue(clock.now()-start<1000);assertEquals("done",r.stdout);assertTrue(r.descendantsMayRemain)
    }
    @Test fun singleDeviceRunAndResourceLockPreventParallelEffects() {
        val time = Time(); val authority = ManualTestAuthority(time)
        val entered = CountDownLatch(1); val leave = CountDownLatch(1)
        val backend = FakeBackend { _, _ -> entered.countDown(); leave.await(2, TimeUnit.SECONDS) }
        val l = authority.begin(backend)
        expect(Code.BACKEND_UNAVAILABLE) { authority.begin(backend) }
        val gate = Gateway(authority, backend, Snapshots(time) { Target(null,"unknown","test") }, time)
        val e = Envelope(l.session,l.run,"c","perception.device_info",l.epoch,l.deadline)
        val thread = Thread { gate.execute(e, emptyMap()) }.apply { start() }
        assertTrue(entered.await(1,TimeUnit.SECONDS)); assertEquals(Code.BACKEND_UNAVAILABLE,gate.execute(e.copy(toolCallId="other"),emptyMap()).error)
        leave.countDown(); thread.join(2000); assertFalse(thread.isAlive)
    }
    @Test fun displayRolesRequireTwoDistinctKnownDisplays() {
        val s = Snapshots(Time()) { Target("app", "valid", "v1") }; s.update(listOf(upper, lower))
        expect(Code.DISPLAY_GONE) { s.capture() }
        expect(Code.INVALID_ARGUMENT) { s.calibrate(7,7) }
        expect(Code.DISPLAY_GONE) { s.calibrate(7,99) }
        s.calibrate(7,9); assertEquals(7,s.capture().display.id)
    }
    @Test fun rotationGeometryRemovalAndAppChangeInvalidateSnapshots() {
        val time = Time(); var target = Target("game","valid","v1"); val s = Snapshots(time) { target }
        s.update(listOf(upper,lower)); s.calibrate(7,9); val first=s.capture()
        target=target.copy(revision="content-change"); expect(Code.TARGET_CHANGED) { s.validate(first.id) }
        val next=s.capture(); target=target.copy(packageName="other"); expect(Code.TARGET_CHANGED) { s.validate(next.id) }
        s.update(listOf(upper.copy(rotation=1,width=600,height=800),lower)); expect(Code.TARGET_CHANGED) { s.validate(next.id) }; expect(Code.DISPLAY_GONE) { s.capture() }
        s.calibrate(7,9); val rotated=s.capture(); s.update(listOf(lower)); expect(Code.TARGET_CHANGED) { s.validate(rotated.id) }
    }
    @Test fun snapshotsExpireAndCacheIsBounded() {
        val time=Time();val s=Snapshots(time) { Target("game","valid","v1") }; s.update(listOf(upper,lower));s.calibrate(7,9)
        val first=s.capture(); repeat(32) { s.capture() }; expect(Code.TARGET_CHANGED) { s.validate(first.id) }
        val last=s.capture(); time.time += 10_000; expect(Code.TARGET_CHANGED) { s.validate(last.id) }
    }
    @Test fun staleTargetNeverReachesInputBackend() {
        val time=Time();val authority=ManualTestAuthority(time);authority.set(Capability.SCREEN_CONTROL,true)
        var target=Target("game","valid","v1");val s=Snapshots(time) { target };s.update(listOf(upper,lower));s.calibrate(7,9);val snapshot=s.capture()
        target=target.copy(revision="v2");val b=FakeBackend();val l=authority.begin(b)
        val r=Gateway(authority,b,s,time).execute(Envelope(l.session,l.run,"c","input.tap",l.epoch,l.deadline,snapshot.id),emptyMap())
        assertEquals(Code.TARGET_CHANGED,r.error);assertEquals(0,b.executions)
    }
    @Test fun freshReadGetsNewIdentityAndExpiryWithoutRevivingOldInputSnapshot() {
        val time=Time();var target=Target("game","valid","old");val s=Snapshots(time) {target}
        s.update(listOf(upper,lower));s.calibrate(7,9);val old=s.capture()
        time.time+=11_000;target=Target("current.app","valid","new")
        val fresh=s.captureForRead(old.id)
        assertNotEquals(old.id,fresh.id);assertEquals(target,fresh.target)
        assertEquals(time.time+10_000,fresh.expires)
        expect(Code.TARGET_CHANGED) {s.validate(old.id)}
        assertEquals(fresh,s.validate(fresh.id))
        time.time+=10_000;expect(Code.TARGET_CHANGED) {s.validate(fresh.id)}
    }
    @Test fun freshReadCannotCrossCalibrationRotationRemovalOrInventedSource() {
        val s=Snapshots(Time()) {Target("game","valid","v1")};s.update(listOf(upper,lower));s.calibrate(7,9)
        expect(Code.TARGET_CHANGED) {s.captureForRead("invented")}
        val old=s.capture();s.calibrate(9,7);expect(Code.TARGET_CHANGED) {s.captureForRead(old.id)}
        val swapped=s.capture();s.update(listOf(upper,lower.copy(rotation=1)));expect(Code.TARGET_CHANGED) {s.captureForRead(swapped.id)}
        s.calibrate(7,9);val last=s.capture();s.update(listOf(lower));expect(Code.TARGET_CHANGED) {s.captureForRead(last.id)}
    }
    @Test fun onlyCaptureUsesCurrentReadAndStaleInputAndTreeStillStopBeforeBackend() {
        val time=Time();val a=ManualTestAuthority(time);a.set(Capability.SCREEN_CONTROL,true)
        var target=Target("game","valid","v1");val s=Snapshots(time) {target};s.update(listOf(upper,lower));s.calibrate(7,9)
        val old=s.capture();target=target.copy(revision="v2")
        var executions=0
        val backend=object:DeviceBackend {
            override fun availability(toolId: String)=Availability(Status.READY,"test","test")
            override fun execute(envelope: Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                executions++;val observed=s.captureForRead(envelope.snapshotId)
                return ToolResult(time.now(),"test",availability(envelope.toolId),snapshotId=observed.id)
            }
            override fun release(runId:String)="test"
        }
        val lease=a.begin(backend);val gate=Gateway(a,backend,s,time)
        fun envelope(tool: String)=Envelope(lease.session,lease.run,tool,tool,lease.epoch,lease.deadline,old.id)
        assertNull(gate.execute(envelope("perception.capture_screen"),emptyMap()).error)
        assertEquals(1,executions)
        assertEquals(Code.TARGET_CHANGED,gate.execute(envelope("perception.capture_screen").copy(snapshotId="invented"),emptyMap()).error)
        for(tool in listOf("input.tap","input.long_press","input.swipe","perception.ui_tree")) {
            assertEquals(Code.TARGET_CHANGED,gate.execute(envelope(tool),emptyMap()).error)
        }
        assertEquals(1,executions)
    }
    @Test fun freshObservationStillBecomesInvalidImmediatelyWhenTargetChangesAgain() {
        val sTime=Time();var target=Target("game","valid","v1");val s=Snapshots(sTime) {target};s.update(listOf(upper,lower));s.calibrate(7,9)
        val fresh=s.captureForRead(s.capture().id);target=target.copy(revision="changed-during-image-callback")
        expect(Code.TARGET_CHANGED) {s.validate(fresh.id)}
    }
    @Test fun captureGatewayDiscardsMissingOrChangedObservationBeforeReturningPixels() {
        for(mode in listOf("missing","changed","old-identity")) {
            val time=Time();var target=Target("game","valid","v1");val s=Snapshots(time) {target}
            s.update(listOf(upper,lower));s.calibrate(7,9);val source=s.capture()
            val backend=object:DeviceBackend {
                override fun availability(toolId:String)=Availability(Status.READY,"test","test")
                override fun execute(envelope:Envelope,args:Map<String,Any?>,checkpoint:Checkpoint):ToolResult {
                    val fresh=s.captureForRead(envelope.snapshotId)
                    if(mode=="changed")target=target.copy(revision="after-image")
                    return ToolResult(time.now(),"test",availability(envelope.toolId),mapOf("pixels" to "must-not-escape"),snapshotId=when(mode) {"missing" -> null;"old-identity" -> source.id;else -> fresh.id})
                }
                override fun release(runId:String)="test"
            }
            val authority=ManualTestAuthority(time);val lease=authority.begin(backend)
            val result=Gateway(authority,backend,s,time).execute(Envelope(lease.session,lease.run,"capture","perception.capture_screen",lease.epoch,lease.deadline,source.id),emptyMap())
            assertEquals(Code.TARGET_CHANGED,result.error);assertTrue(result.data.isEmpty())
        }
    }
    @Test fun gestureRejectsDurationOversizedPathNanAndOffscreen() {
        expect(Code.INVALID_ARGUMENT) { Gesture(listOf(Point(1.0,1.0)),5001).validate(upper) }
        expect(Code.INVALID_ARGUMENT) { Gesture(listOf(Point(Double.NaN,1.0)),50).validate(upper) }
        expect(Code.INVALID_ARGUMENT) { Gesture(listOf(Point(800.0,1.0)),50).validate(upper) }
        expect(Code.LIMIT_EXCEEDED) { Gesture(List(33) { Point(1.0,1.0) },50).validate(upper) }
        Gesture(listOf(Point(799.9,599.9)),5000).validate(upper)
    }
    @Test fun screenshotCropScalingHasIndependentExpectedCoordinates() {
        val transform=FrameTransform(100,50,600,400,300,200)
        assertEquals(Point(400.0,250.0),transform.toDisplay(Point(150.0,100.0)))
        expect(Code.INVALID_ARGUMENT) { transform.toDisplay(Point(300.0,0.0)) }
    }
    @Test fun byteBudgetDistinguishesExactEofAndTruncationAndChecksCancellation() {
        assertFalse(Budgets.read(ByteArray(10).inputStream(),10).truncated)
        assertTrue(Budgets.read(ByteArray(11).inputStream(),10).truncated)
        expect(Code.CANCELLED) { Budgets.read(ByteArray(11).inputStream(),10) { fail(Code.CANCELLED,"test") } }
        expect(Code.LIMIT_EXCEEDED) { Budgets.read(ByteArray(10).inputStream(),0) }
        assertFalse(Budgets.safeText("Authorization: Bearer abc-secret api_key=xyz").contains("abc-secret"))
    }
    private class Fixture {
        val parent=Files.createTempDirectory("thor-toolpack-test")
        val files=LocalFiles(parent.resolve("configs"))
        val path=files.root.resolve("sample.json")
        val old="{\"volume\":50,\"unknown\":\"preserved\"}\r\n".toByteArray()
        val next="{\"volume\":51,\"unknown\":\"preserved\"}\r\n".toByteArray()
        val key=SecretKeySpec(ByteArray(32) { it.toByte() },"AES")
        val vault=EncryptedVault(parent.resolve("backups"),key,Time())
        val tx=ConfigTransactions(files,vault) { bytes -> requireTool(bytes.toString(Charsets.UTF_8).startsWith("{"),message="test format") }
        val cp=Checkpoint({},Cancellation())
        init { Files.write(path,old) }
        fun approval(before:ByteArray=old,after:ByteArray=next)=Approval(path.toString(),hash(before),hash(after))
        fun backup()=tx.backup(path.toString(),hash(old),cp)
    }
    @Test fun pathTraversalSymlinksAndNonexistentTargetsAreDenied() {
        val f=Fixture(); Files.write(f.parent.resolve("outside"),f.old)
        expect(Code.PERMISSION_MISSING) { f.files.read("../outside") }
        expect(Code.PERMISSION_MISSING) { f.files.read(f.parent.resolve("outside").toString()) }
        Files.createSymbolicLink(f.files.root.resolve("link"),f.path)
        expect(Code.PERMISSION_MISSING) { f.files.read("link") }
        Files.createSymbolicLink(f.files.root.resolve("parent-link"),f.files.root)
        expect(Code.PERMISSION_MISSING) { f.files.read("parent-link/sample.json") }
        expect(Code.IO_ERROR) { f.files.read("absent.json") }
    }
    @Test fun encryptedBackupSurvivesReopenAndTamperFails() {
        val f=Fixture();val b=f.backup();val onDisk=Files.readAllBytes(f.parent.resolve("backups/${b.id}.backup"))
        assertFalse(onDisk.toString(Charsets.ISO_8859_1).contains("preserved"))
        assertArrayEquals(f.old,EncryptedVault(f.parent.resolve("backups"),f.key,Time()).load(b.id).bytes)
        onDisk[20]=(onDisk[20].toInt() xor 1).toByte();Files.write(f.parent.resolve("backups/${b.id}.backup"),onDisk)
        expect(Code.IO_ERROR) { f.vault.load(b.id) }
        expect(Code.INVALID_ARGUMENT) { f.vault.load("../../anything") }
    }
    @Test fun backupWriteVerifyRestorePreservesUnknownFieldsAndBeforeRestoreBackup() {
        val f=Fixture();val original=f.backup()
        assertTrue(original.metadata.containsKey("permissions"));assertTrue(original.metadata.containsKey("mtime"))
        val written=f.tx.write(f.path.toString(),hash(f.old),f.next,original.id,f.approval(),f.cp)
        assertTrue(written.atomic && written.verified);assertArrayEquals(f.next,Files.readAllBytes(f.path))
        val restored=f.tx.restore(original.id,hash(f.next),f.approval(f.next,f.old),f.cp)
        assertEquals(hash(f.old),restored.newHash);assertArrayEquals(f.old,Files.readAllBytes(f.path));assertArrayEquals(f.next,f.vault.load(restored.backupId).bytes)
    }
    @Test fun writesRequireExactApprovalMatchingBackupAndCurrentHash() {
        val f=Fixture();val b=f.backup()
        expect(Code.APPROVAL_REQUIRED) { f.tx.write(f.path.toString(),hash(f.old),f.next,b.id,null,f.cp) }
        expect(Code.APPROVAL_REQUIRED) { f.tx.write(f.path.toString(),hash(f.old),f.next,b.id,f.approval().copy(newHash="fake"),f.cp) }
        val wrong=f.vault.save(f.files.root.resolve("another.json").toString(),f.old)
        expect(Code.HASH_CONFLICT) { f.tx.write(f.path.toString(),hash(f.old),f.next,wrong.id,f.approval(),f.cp) }
        Files.write(f.path,"concurrent".toByteArray())
        expect(Code.HASH_CONFLICT) { f.tx.write(f.path.toString(),hash(f.old),f.next,b.id,f.approval(),f.cp) }
        assertEquals("concurrent",Files.readString(f.path))
    }
    @Test fun finalCommitCheckDetectsConcurrentWriterAndSymlinkSwap() {
        val f=Fixture();val b=f.backup();var checks=0
        val cp=Checkpoint({ checks++; if(checks==3) Files.write(f.path,"other-writer".toByteArray()) },Cancellation())
        expect(Code.HASH_CONFLICT) { f.tx.write(f.path.toString(),hash(f.old),f.next,b.id,f.approval(),cp) }
        assertEquals("other-writer",Files.readString(f.path));assertFalse(cp.submitted)
        val g=Fixture();val backup=g.backup();checks=0
        val swap=Checkpoint({ checks++;if(checks==3) { Files.delete(g.path);Files.createSymbolicLink(g.path,g.parent.resolve("outside"));Files.write(g.parent.resolve("outside"),g.old) } },Cancellation())
        expect(Code.PERMISSION_MISSING) { g.tx.write(g.path.toString(),hash(g.old),g.next,backup.id,g.approval(),swap) }
        assertArrayEquals(g.old,Files.readAllBytes(g.parent.resolve("outside")))
    }
    @Test fun cancelledCommitDoesNotWriteOrAutoRestore() {
        val f=Fixture();val b=f.backup();val c=Cancellation();var checks=0
        val cp=Checkpoint({ checks++;if(checks==3)c.cancel() },c)
        expect(Code.CANCELLED) { f.tx.write(f.path.toString(),hash(f.old),f.next,b.id,f.approval(),cp) }
        assertArrayEquals(f.old,Files.readAllBytes(f.path))
        Files.write(f.path,f.next)
        expect(Code.CANCELLED) { f.tx.restore(b.id,hash(f.next),f.approval(f.next,f.old),cp) }
        assertArrayEquals(f.next,Files.readAllBytes(f.path))
    }
    @Test fun postCommitCorruptionIsFailureWithRecoveryAndSubmittedMarker() {
        val f=Fixture();val b=f.backup();var reads=0
        val raced=object:ConfigFilePort by f.files {
            override fun read(value:String,maxBytes:Int):ByteArray {
                reads++;if(reads==2)Files.write(f.path,"overwrite-after-commit".toByteArray())
                return f.files.read(value,maxBytes)
            }
        }
        val tx=ConfigTransactions(raced,f.vault) {}
        expect(Code.IO_ERROR) { tx.write(f.path.toString(),hash(f.old),f.next,b.id,f.approval(),f.cp) }
        assertTrue(f.cp.submitted);assertArrayEquals(f.old,f.vault.load(b.id).bytes)
        assertEquals("overwrite-after-commit",Files.readString(f.path))
    }
    @Test fun restoreConflictPreservesCurrentFileAndBackupQuotaRefusesWithoutDeleting() {
        val f=Fixture();val b=f.backup();Files.write(f.path,f.next)
        expect(Code.APPROVAL_REQUIRED) { f.tx.restore(b.id,hash(f.old),f.approval(f.next,f.old),f.cp) }
        repeat(9) { f.vault.save(f.path.toString(),f.old) }
        expect(Code.LIMIT_EXCEEDED) { f.vault.save(f.path.toString(),f.old) }
        assertArrayEquals(f.old,f.vault.load(b.id).bytes)
        val tiny=EncryptedVault(f.parent.resolve("tiny"),f.key,Time(),maxTotalBytes=10)
        expect(Code.LIMIT_EXCEEDED) { tiny.save(f.path.toString(),f.old) }
    }
    @Test fun configurationSizeAndFormatLimitsPreventWrites() {
        val f=Fixture();val b=f.backup()
        expect(Code.LIMIT_EXCEEDED) { f.tx.write(f.path.toString(),hash(f.old),ByteArray(Budgets.CONFIG),b.id,null,f.cp) }
        val invalid="invalid".toByteArray()
        expect(Code.INVALID_ARGUMENT) { f.tx.write(f.path.toString(),hash(f.old),invalid,b.id,f.approval(f.old,invalid),f.cp) }
        assertArrayEquals(f.old,Files.readAllBytes(f.path))
    }
    @Test fun shellOutputIsCombinedBoundedAndNonzeroExitPreserved() {
        val clock=Clock { System.nanoTime()/1_000_000 }; val shell=BoundedShell("/bin/sh",clock)
        val r=shell.execute("printf 'abc'; printf 'def' >&2; exit 7",1000,4,Cancellation()) {}
        assertEquals(7,r.exitCode);assertTrue(r.truncated);assertTrue(r.stdout.length+r.stderr.length<=4);assertNull(r.error)
        expect(Code.LIMIT_EXCEEDED) { shell.execute("true",60001,10,Cancellation()) {} }
    }
    @Test fun shellTimeoutAndCancellationStopDirectChildWithoutClaimingDescendants() {
        val clock=Clock { System.nanoTime()/1_000_000 };val shell=BoundedShell("/bin/sh",clock)
        val timeout=shell.execute("exec sleep 2",100,128,Cancellation()) {}
        assertEquals(Code.TIMEOUT,timeout.error);assertFalse(timeout.terminationConfirmed);assertTrue(timeout.descendantsMayRemain);assertTrue(timeout.durationMs<1500)
        val c=Cancellation();val stopped=Thread { Thread.sleep(100);c.cancel() }.apply { start() }
        val r=shell.execute("exec sleep 2",1000,128,c) {};stopped.join()
        assertTrue(r.error==Code.CANCELLED || r.exitCode!=0);assertFalse(r.terminationConfirmed)
    }
}

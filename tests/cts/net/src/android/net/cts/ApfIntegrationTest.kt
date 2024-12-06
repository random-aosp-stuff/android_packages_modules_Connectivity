/*
 * Copyright (C) 2024 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
// ktlint does not allow annotating function argument literals inline. Disable the specific rule
// since this negatively affects readability.
@file:Suppress("ktlint:standard:comment-wrapping")

package android.net.cts

import android.Manifest.permission.WRITE_DEVICE_CONFIG
import android.content.pm.PackageManager.FEATURE_WIFI
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.apf.ApfCapabilities
import android.net.apf.ApfConstants.ETH_ETHERTYPE_OFFSET
import android.net.apf.ApfConstants.ETH_HEADER_LEN
import android.net.apf.ApfConstants.ICMP6_CHECKSUM_OFFSET
import android.net.apf.ApfConstants.ICMP6_TYPE_OFFSET
import android.net.apf.ApfConstants.IPV6_DEST_ADDR_OFFSET
import android.net.apf.ApfConstants.IPV6_HEADER_LEN
import android.net.apf.ApfConstants.IPV6_NEXT_HEADER_OFFSET
import android.net.apf.ApfConstants.IPV6_SRC_ADDR_OFFSET
import android.net.apf.ApfCounterTracker
import android.net.apf.ApfCounterTracker.Counter.DROPPED_IPV6_MULTICAST_PING
import android.net.apf.ApfCounterTracker.Counter.FILTER_AGE_16384THS
import android.net.apf.ApfCounterTracker.Counter.PASSED_IPV6_ICMP
import android.net.apf.ApfV4Generator
import android.net.apf.ApfV4GeneratorBase
import android.net.apf.ApfV6Generator
import android.net.apf.BaseApfGenerator
import android.net.apf.BaseApfGenerator.MemorySlot
import android.net.apf.BaseApfGenerator.Register.R0
import android.net.apf.BaseApfGenerator.Register.R1
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import android.provider.DeviceConfig.NAMESPACE_CONNECTIVITY
import android.system.Os
import android.system.OsConstants
import android.system.OsConstants.AF_INET6
import android.system.OsConstants.ETH_P_IPV6
import android.system.OsConstants.IPPROTO_ICMPV6
import android.system.OsConstants.SOCK_DGRAM
import android.system.OsConstants.SOCK_NONBLOCK
import android.util.Log
import androidx.test.filters.RequiresDevice
import androidx.test.platform.app.InstrumentationRegistry
import com.android.compatibility.common.util.PropertyUtil.getFirstApiLevel
import com.android.compatibility.common.util.PropertyUtil.getVsrApiLevel
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.android.compatibility.common.util.SystemUtil.runShellCommandOrThrow
import com.android.compatibility.common.util.VsrTest
import com.android.internal.util.HexDump
import com.android.net.module.util.NetworkStackConstants.ETHER_ADDR_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_DST_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ETHER_HEADER_LEN
import com.android.net.module.util.NetworkStackConstants.ETHER_SRC_ADDR_OFFSET
import com.android.net.module.util.NetworkStackConstants.ICMPV6_HEADER_MIN_LEN
import com.android.net.module.util.NetworkStackConstants.IPV6_ADDR_LEN
import com.android.net.module.util.PacketReader
import com.android.testutils.DevSdkIgnoreRule
import com.android.testutils.DevSdkIgnoreRule.IgnoreUpTo
import com.android.testutils.DevSdkIgnoreRunner
import com.android.testutils.NetworkStackModuleTest
import com.android.testutils.RecorderCallback.CallbackEntry.Available
import com.android.testutils.RecorderCallback.CallbackEntry.LinkPropertiesChanged
import com.android.testutils.SkipPresubmit
import com.android.testutils.TestableNetworkCallback
import com.android.testutils.runAsShell
import com.android.testutils.waitForIdle
import com.google.common.truth.Expect
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import com.google.common.truth.TruthJUnit.assume
import java.io.FileDescriptor
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.random.Random
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import org.junit.After
import org.junit.AfterClass
import org.junit.Before
import org.junit.BeforeClass
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

private const val TAG = "ApfIntegrationTest"
private const val TIMEOUT_MS = 2000L
private const val APF_NEW_RA_FILTER_VERSION = "apf_new_ra_filter_version"
private const val POLLING_INTERVAL_MS: Int = 100
private const val RCV_BUFFER_SIZE = 1480
private const val PING_HEADER_LENGTH = 8

@AppModeFull(reason = "CHANGE_NETWORK_STATE permission can't be granted to instant apps")
@RunWith(DevSdkIgnoreRunner::class)
@RequiresDevice
@NetworkStackModuleTest
// ByteArray.toHexString is experimental API
@kotlin.ExperimentalStdlibApi
class ApfIntegrationTest {
    companion object {
        private val PING_DESTINATION = InetSocketAddress("2001:4860:4860::8888", 0)

        private val context = InstrumentationRegistry.getInstrumentation().context
        private val powerManager = context.getSystemService(PowerManager::class.java)!!
        private val wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, TAG)

        fun pollingCheck(condition: () -> Boolean, timeout_ms: Int): Boolean {
            var polling_time = 0
            do {
                Thread.sleep(POLLING_INTERVAL_MS.toLong())
                polling_time += POLLING_INTERVAL_MS
                if (condition()) return true
            } while (polling_time < timeout_ms)
            return false
        }

        fun turnScreenOff() {
            if (!wakeLock.isHeld()) wakeLock.acquire()
            runShellCommandOrThrow("input keyevent KEYCODE_SLEEP")
            val result = pollingCheck({ !powerManager.isInteractive() }, timeout_ms = 2000)
            assertThat(result).isTrue()
        }

        fun turnScreenOn() {
            if (wakeLock.isHeld()) wakeLock.release()
            runShellCommandOrThrow("input keyevent KEYCODE_WAKEUP")
            val result = pollingCheck({ powerManager.isInteractive() }, timeout_ms = 2000)
            assertThat(result).isTrue()
        }

        @BeforeClass
        @JvmStatic
        @Suppress("ktlint:standard:no-multi-spaces")
        fun setupOnce() {
            // TODO: assertions thrown in @BeforeClass / @AfterClass are not well supported in the
            // test infrastructure. Consider saving excepion and throwing it in setUp().
            // APF must run when the screen is off and the device is not interactive.
            turnScreenOff()
            // Wait for APF to become active.
            Thread.sleep(1000)
            // TODO: check that there is no active wifi network. Otherwise, ApfFilter has already been
            // created.
            // APF adb cmds are only implemented in ApfFilter.java. Enable experiment to prevent
            // LegacyApfFilter.java from being used.
            runAsShell(WRITE_DEVICE_CONFIG) {
                DeviceConfig.setProperty(
                        NAMESPACE_CONNECTIVITY,
                        APF_NEW_RA_FILTER_VERSION,
                        "1",  // value => force enabled
                        false // makeDefault
                )
            }
        }

        @AfterClass
        @JvmStatic
        fun tearDownOnce() {
            turnScreenOn()
        }
    }

    class Icmp6PacketReader(
            handler: Handler,
            private val network: Network
    ) : PacketReader(handler, RCV_BUFFER_SIZE) {
        private var sockFd: FileDescriptor? = null
        private var futureReply: CompletableFuture<ByteArray>? = null

        override fun createFd(): FileDescriptor {
            // sockFd is closed by calling super.stop()
            val sock = Os.socket(AF_INET6, SOCK_DGRAM or SOCK_NONBLOCK, IPPROTO_ICMPV6)
            // APF runs only on WiFi, so make sure the socket is bound to the right network.
            network.bindSocket(sock)
            sockFd = sock
            return sock
        }

        override fun handlePacket(recvbuf: ByteArray, length: Int) {
            // If zero-length or Type is not echo reply: ignore.
            if (length == 0 || recvbuf[0] != 0x81.toByte()) {
                return
            }
            // Only copy the ping data and complete the future.
            val result = recvbuf.sliceArray(8..<length)
            Log.i(TAG, "Received ping reply: ${result.toHexString()}")
            futureReply!!.complete(recvbuf.sliceArray(8..<length))
        }

        fun sendPing(data: ByteArray, payloadSize: Int) {
            require(data.size == payloadSize)

            // rfc4443#section-4.1: Echo Request Message
            //   0                   1                   2                   3
            //   0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |     Type      |     Code      |          Checksum             |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |           Identifier          |        Sequence Number        |
            //  +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
            //  |     Data ...
            //  +-+-+-+-+-
            val icmp6Header = byteArrayOf(0x80.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
            val packet = icmp6Header + data
            Log.i(TAG, "Sent ping: ${packet.toHexString()}")
            futureReply = CompletableFuture<ByteArray>()
            Os.sendto(sockFd!!, packet, 0, packet.size, 0, PING_DESTINATION)
        }

        fun expectPingReply(timeoutMs: Long = TIMEOUT_MS): ByteArray {
            return futureReply!!.get(timeoutMs, TimeUnit.MILLISECONDS)
        }

        fun expectPingDropped() {
            assertFailsWith(TimeoutException::class) {
                futureReply!!.get(TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }
        }

        override fun start(): Boolean {
            // Ignore the fact start() could return false or throw an exception.
            handler.post({ super.start() })
            handler.waitForIdle(TIMEOUT_MS)
            return true
        }

        override fun stop() {
            handler.post({ super.stop() })
            handler.waitForIdle(TIMEOUT_MS)
        }
    }

    @get:Rule val ignoreRule = DevSdkIgnoreRule()
    @get:Rule val expect = Expect.create()

    private val cm by lazy { context.getSystemService(ConnectivityManager::class.java)!! }
    private val pm by lazy { context.packageManager }
    private lateinit var network: Network
    private lateinit var ifname: String
    private lateinit var networkCallback: TestableNetworkCallback
    private lateinit var caps: ApfCapabilities
    private val handlerThread = HandlerThread("$TAG handler thread").apply { start() }
    private val handler = Handler(handlerThread.looper)
    private lateinit var packetReader: Icmp6PacketReader

    fun getApfCapabilities(): ApfCapabilities {
        val caps = runShellCommand("cmd network_stack apf $ifname capabilities").trim()
        if (caps.isEmpty()) {
            return ApfCapabilities(0, 0, 0)
        }
        val (version, maxLen, packetFormat) = caps.split(",").map { it.toInt() }
        return ApfCapabilities(version, maxLen, packetFormat)
    }

    @Before
    fun setUp() {
        assume().that(pm.hasSystemFeature(FEATURE_WIFI)).isTrue()

        networkCallback = TestableNetworkCallback()
        cm.requestNetwork(
                NetworkRequest.Builder()
                        .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        .build(),
                networkCallback
        )
        network = networkCallback.expect<Available>().network
        networkCallback.eventuallyExpect<LinkPropertiesChanged>(TIMEOUT_MS) {
            ifname = assertNotNull(it.lp.interfaceName)
            true
        }
        // It's possible the device does not support APF, in which case this command will not be
        // successful. Ignore the error as testApfCapabilities() already asserts APF support on the
        // respective VSR releases and all other tests are based on the capabilities indicated.
        runShellCommand("cmd network_stack apf $ifname pause")
        caps = getApfCapabilities()

        packetReader = Icmp6PacketReader(handler, network)
        packetReader.start()
    }

    @After
    fun tearDown() {
        if (::packetReader.isInitialized) {
            packetReader.stop()
        }
        handlerThread.quitSafely()
        handlerThread.join()

        if (::ifname.isInitialized) {
            runShellCommand("cmd network_stack apf $ifname resume")
        }
        if (::networkCallback.isInitialized) {
            cm.unregisterNetworkCallback(networkCallback)
        }
    }

    @VsrTest(
        requirements = ["VSR-5.3.12-001", "VSR-5.3.12-003", "VSR-5.3.12-004", "VSR-5.3.12-009",
            "VSR-5.3.12-012"]
    )
    @Test
    fun testApfCapabilities() {
        // APF became mandatory in Android 14 VSR.
        assume().that(getVsrApiLevel()).isAtLeast(34)

        // ApfFilter does not support anything but ARPHRD_ETHER.
        assertThat(caps.apfPacketFormat).isEqualTo(OsConstants.ARPHRD_ETHER)

        // DEVICEs launching with Android 14 with CHIPSETs that set ro.board.first_api_level to 34:
        // - [GMS-VSR-5.3.12-003] MUST return 4 or higher as the APF version number from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // - [GMS-VSR-5.3.12-004] MUST indicate at least 1024 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        // TODO: check whether above text should be changed "34 or higher"
        assertThat(caps.apfVersionSupported).isAtLeast(4)
        assertThat(caps.maximumApfProgramSize).isAtLeast(1024)

        if (caps.apfVersionSupported > 4) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2048)
            assertThat(caps.apfVersionSupported).isEqualTo(6000) // v6.0000
        }

        // DEVICEs launching with Android 15 (AOSP experimental) or higher with CHIPSETs that set
        // ro.board.first_api_level or ro.board.api_level to 202404 or higher:
        // - [GMS-VSR-5.3.12-009] MUST indicate at least 2048 bytes of usable memory from calls to
        //   the getApfPacketFilterCapabilities HAL method.
        if (getVsrApiLevel() >= 202404) {
            assertThat(caps.maximumApfProgramSize).isAtLeast(2048)
        }
    }

    // APF is backwards compatible, i.e. a v6 interpreter supports both v2 and v4 functionality.
    fun assumeApfVersionSupportAtLeast(version: Int) {
        assume().that(caps.apfVersionSupported).isAtLeast(version)
    }

    fun installProgram(bytes: ByteArray) {
        val prog = bytes.toHexString()
        val result = runShellCommandOrThrow("cmd network_stack apf $ifname install $prog").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(result).isEqualTo("success")
    }

    fun readProgram(): ByteArray {
        val progHexString = runShellCommandOrThrow("cmd network_stack apf $ifname read").trim()
        // runShellCommandOrThrow only throws on S+.
        assertThat(progHexString).isNotEmpty()
        return HexDump.hexStringToByteArray(progHexString)
    }

    @VsrTest(
            requirements = ["VSR-5.3.12-007", "VSR-5.3.12-008", "VSR-5.3.12-010", "VSR-5.3.12-011"]
    )
    @SkipPresubmit(reason = "This test takes longer than 1 minute, do not run it on presubmit.")
    // APF integration is mostly broken before V, only run the full read / write test on V+.
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    // Increase timeout for test to 15 minutes to accommodate device with large APF RAM.
    @Test(timeout = 15 * 60 * 1000)
    fun testReadWriteProgram() {
        assumeApfVersionSupportAtLeast(4)

        val minReadWriteSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            2
        } else {
            8
        }

        // The minReadWriteSize is 2 bytes. The first byte always stays PASS.
        val program = ByteArray(caps.maximumApfProgramSize)
        for (i in caps.maximumApfProgramSize downTo minReadWriteSize) {
            // Randomize bytes in range [1, i). And install first [0, i) bytes of program.
            // Note that only the very first instruction (PASS) is valid APF bytecode.
            Random.nextBytes(program, 1 /* fromIndex */, i /* toIndex */)
            installProgram(program.sliceArray(0..<i))

            // Compare entire memory region.
            val readResult = readProgram()
            val errMsg = """
                read/write $i byte prog failed.
                In APFv4, the APF memory region MUST NOT be modified or cleared except by APF
                instructions executed by the interpreter or by Android OS calls to the HAL. If this
                requirement cannot be met, the firmware cannot declare that it supports APFv4 and
                it should declare that it only supports APFv3(if counter is partially supported) or
                APFv2.
            """.trimIndent()
            assertWithMessage(errMsg).that(readResult).isEqualTo(program)
        }
    }

    private fun installAndVerifyProgram(program: ByteArray) {
        installProgram(program)
        val readResult = readProgram().take(program.size).toByteArray()
        assertThat(readResult).isEqualTo(program)
    }

    fun ApfV4GeneratorBase<*>.addPassIfNotIcmpv6EchoReply() {
        // If not IPv6 -> PASS
        addLoad16(R0, ETH_ETHERTYPE_OFFSET)
        addJumpIfR0NotEquals(ETH_P_IPV6.toLong(), BaseApfGenerator.PASS_LABEL)

        // If not ICMPv6 -> PASS
        addLoad8(R0, IPV6_NEXT_HEADER_OFFSET)
        addJumpIfR0NotEquals(IPPROTO_ICMPV6.toLong(), BaseApfGenerator.PASS_LABEL)

        // If not echo reply -> PASS
        addLoad8(R0, ICMP6_TYPE_OFFSET)
        addJumpIfR0NotEquals(0x81, BaseApfGenerator.PASS_LABEL)
    }

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testDropPingReply() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        assume().that(getVsrApiLevel()).isAtLeast(34)
        assumeApfVersionSupportAtLeast(4)

        // clear any active APF filter
        clearApfMemory()
        readProgram() // wait for install completion

        // Assert that initial ping does not get filtered.
        val payloadSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            68
        } else {
            4
        }
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        assertThat(packetReader.expectPingReply()).isEqualTo(data)

        // Generate an APF program that drops the next ping
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply()

        // if not data matches -> PASS
        gen.addLoadImmediate(R0, ICMP6_TYPE_OFFSET + PING_HEADER_LENGTH)
        gen.addJumpIfBytesAtR0NotEqual(data, BaseApfGenerator.PASS_LABEL)

        // else DROP
        gen.addJump(BaseApfGenerator.DROP_LABEL)

        val program = gen.generate()
        installAndVerifyProgram(program)

        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingDropped()
    }

    fun clearApfMemory() = installProgram(ByteArray(caps.maximumApfProgramSize))

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testPrefilledMemorySlotsV4() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        assume().that(getVsrApiLevel()).isAtLeast(34)
        // Test v4 memory slots on both v4 and v6 interpreters.
        assumeApfVersionSupportAtLeast(4)
        clearApfMemory()
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply()

        // Store all prefilled memory slots in counter region [500, 520)
        val counterRegion = 500
        gen.addLoadImmediate(R1, counterRegion)
        gen.addLoadFromMemory(R0, MemorySlot.PROGRAM_SIZE)
        gen.addStoreData(R0, 0)
        gen.addLoadFromMemory(R0, MemorySlot.RAM_LEN)
        gen.addStoreData(R0, 4)
        gen.addLoadFromMemory(R0, MemorySlot.IPV4_HEADER_SIZE)
        gen.addStoreData(R0, 8)
        gen.addLoadFromMemory(R0, MemorySlot.PACKET_SIZE)
        gen.addStoreData(R0, 12)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_SECONDS)
        gen.addStoreData(R0, 16)

        val program = gen.generate()
        assertThat(program.size).isLessThan(counterRegion)
        installAndVerifyProgram(program)

        // Trigger the program by sending a ping and waiting on the reply.
        val payloadSize = if (getFirstApiLevel() >= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            68
        } else {
            4
        }
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        val readResult = readProgram()
        val buffer = ByteBuffer.wrap(readResult, counterRegion, 20 /* length */)
        expect.withMessage("PROGRAM_SIZE").that(buffer.getInt()).isEqualTo(program.size)
        expect.withMessage("RAM_LEN").that(buffer.getInt()).isEqualTo(caps.maximumApfProgramSize)
        expect.withMessage("IPV4_HEADER_SIZE").that(buffer.getInt()).isEqualTo(0)
        // Ping packet payload + ICMPv6 header (8)  + IPv6 header (40) + ethernet header (14)
        expect.withMessage("PACKET_SIZE").that(buffer.getInt()).isEqualTo(payloadSize + 8 + 40 + 14)
        expect.withMessage("FILTER_AGE_SECONDS").that(buffer.getInt()).isLessThan(5)
    }

    // APF integration is mostly broken before V
    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testFilterAgeIncreasesBetweenPackets() {
        // VSR-14 mandates APF to be turned on when the screen is off and the Wi-Fi link
        // is idle or traffic is less than 10 Mbps. Before that, we don't mandate when the APF
        // should be turned on.
        assume().that(getVsrApiLevel()).isAtLeast(34)
        assumeApfVersionSupportAtLeast(4)
        clearApfMemory()
        val gen = ApfV4Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply()

        // Store all prefilled memory slots in counter region [500, 520)
        val counterRegion = 500
        gen.addLoadImmediate(R1, counterRegion)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_SECONDS)
        gen.addStoreData(R0, 0)

        installAndVerifyProgram(gen.generate())

        val payloadSize = 56
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        var buffer = ByteBuffer.wrap(readProgram(), counterRegion, 4 /* length */)
        val filterAgeSecondsOrig = buffer.getInt()

        Thread.sleep(5100)

        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        buffer = ByteBuffer.wrap(readProgram(), counterRegion, 4 /* length */)
        val filterAgeSeconds = buffer.getInt()
        // Assert that filter age has increased, but not too much.
        val timeDiff = filterAgeSeconds - filterAgeSecondsOrig
        assertThat(timeDiff).isAnyOf(5, 6)
    }

    @VsrTest(requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005"])
    @Test
    fun testFilterAge16384thsIncreasesBetweenPackets() {
        assumeApfVersionSupportAtLeast(6000)
        clearApfMemory()
        val gen = ApfV6Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )

        // If not ICMPv6 Echo Reply -> PASS
        gen.addPassIfNotIcmpv6EchoReply()

        // Store all prefilled memory slots in counter region [500, 520)
        gen.addLoadFromMemory(R0, MemorySlot.FILTER_AGE_16384THS)
        gen.addStoreCounter(FILTER_AGE_16384THS, R0)

        installAndVerifyProgram(gen.generate())

        val payloadSize = 56
        val data = ByteArray(payloadSize).also { Random.nextBytes(it) }
        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        var apfRam = readProgram()
        val filterAge16384thSecondsOrig =
                ApfCounterTracker.getCounterValue(apfRam, FILTER_AGE_16384THS)

        Thread.sleep(5000)

        packetReader.sendPing(data, payloadSize)
        packetReader.expectPingReply()

        apfRam = readProgram()
        val filterAge16384thSeconds = ApfCounterTracker.getCounterValue(apfRam, FILTER_AGE_16384THS)
        val timeDiff = (filterAge16384thSeconds - filterAge16384thSecondsOrig)
        // Expect the HAL plus ping latency to be less than 800ms.
        val timeDiffLowerBound = (4.99 * 16384).toInt()
        val timeDiffUpperBound = (5.81 * 16384).toInt()
        // Assert that filter age has increased, but not too much.
        assertThat(timeDiff).isGreaterThan(timeDiffLowerBound)
        assertThat(timeDiff).isLessThan(timeDiffUpperBound)
    }

    @VsrTest(
            requirements = ["VSR-5.3.12-002", "VSR-5.3.12-005", "VSR-5.3.12-012", "VSR-5.3.12-013",
                "VSR-5.3.12-014", "VSR-5.3.12-015", "VSR-5.3.12-016", "VSR-5.3.12-017"]
    )
    @IgnoreUpTo(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    @Test
    fun testReplyPing() {
        assumeApfVersionSupportAtLeast(6000)
        installProgram(ByteArray(caps.maximumApfProgramSize) { 0 }) // Clear previous program
        readProgram() // Ensure installation is complete

        val payloadSize = 56
        val payload = ByteArray(payloadSize).also { Random.nextBytes(it) }
        val firstByte = payload.take(1).toByteArray()

        val pingRequestIpv6PayloadLen = PING_HEADER_LENGTH + 1
        val pingRequestPktLen = ETH_HEADER_LEN + IPV6_HEADER_LEN + pingRequestIpv6PayloadLen

        val gen = ApfV6Generator(
                caps.apfVersionSupported,
                caps.maximumApfProgramSize,
                caps.maximumApfProgramSize
        )
        val skipPacketLabel = gen.uniqueLabel

        // Summary of the program:
        //   if the packet is not ICMPv6 echo reply
        //     pass
        //   else if the echo reply payload size is 1
        //     increase PASSED_IPV6_ICMP counter
        //     pass
        //   else
        //     transmit a ICMPv6 echo request packet with the first byte of the payload in the reply
        //     increase DROPPED_IPV6_MULTICAST_PING counter
        //     drop
        val program = gen
                .addLoad16(R0, ETH_ETHERTYPE_OFFSET)
                .addJumpIfR0NotEquals(ETH_P_IPV6.toLong(), skipPacketLabel)
                .addLoad8(R0, IPV6_NEXT_HEADER_OFFSET)
                .addJumpIfR0NotEquals(IPPROTO_ICMPV6.toLong(), skipPacketLabel)
                .addLoad8(R0, ICMP6_TYPE_OFFSET)
                .addJumpIfR0NotEquals(0x81, skipPacketLabel) // Echo reply type
                .addLoadFromMemory(R0, MemorySlot.PACKET_SIZE)
                .addCountAndPassIfR0Equals(
                        (ETHER_HEADER_LEN + IPV6_HEADER_LEN + PING_HEADER_LENGTH + firstByte.size)
                                .toLong(),
                        PASSED_IPV6_ICMP
                )
                // Ping Packet Generation
                .addAllocate(pingRequestPktLen)
                // Eth header
                .addPacketCopy(ETHER_SRC_ADDR_OFFSET, ETHER_ADDR_LEN) // dst MAC address
                .addPacketCopy(ETHER_DST_ADDR_OFFSET, ETHER_ADDR_LEN) // src MAC address
                .addWriteU16(ETH_P_IPV6) // IPv6 type
                // IPv6 Header
                .addWrite32(0x60000000) // IPv6 Header: version, traffic class, flowlabel
                // payload length (2 bytes) | next header: ICMPv6 (1 byte) | hop limit (1 byte)
                .addWrite32(pingRequestIpv6PayloadLen shl 16 or (IPPROTO_ICMPV6 shl 8 or 64))
                .addPacketCopy(IPV6_DEST_ADDR_OFFSET, IPV6_ADDR_LEN) // src ip
                .addPacketCopy(IPV6_SRC_ADDR_OFFSET, IPV6_ADDR_LEN) // dst ip
                // ICMPv6
                .addWriteU8(0x80) // type: echo request
                .addWriteU8(0) // code
                .addWriteU16(pingRequestIpv6PayloadLen) // checksum
                // identifier
                .addPacketCopy(ETHER_HEADER_LEN + IPV6_HEADER_LEN + ICMPV6_HEADER_MIN_LEN, 2)
                .addWriteU16(0) // sequence number
                .addDataCopy(firstByte) // data
                .addTransmitL4(
                        ETHER_HEADER_LEN, // ip_ofs
                        ICMP6_CHECKSUM_OFFSET, // csum_ofs
                        IPV6_SRC_ADDR_OFFSET, // csum_start
                        IPPROTO_ICMPV6, // partial_sum
                        false // udp
                )
                // Warning: the program abuse DROPPED_IPV6_MULTICAST_PING for debugging purpose
                .addCountAndDrop(DROPPED_IPV6_MULTICAST_PING)
                .defineLabel(skipPacketLabel)
                .addPass()
                .generate()

        installAndVerifyProgram(program)

        packetReader.sendPing(payload, payloadSize)

        val replyPayload = try {
            packetReader.expectPingReply(TIMEOUT_MS * 2)
        } catch (e: TimeoutException) {
            byteArrayOf() // Empty payload if timeout occurs
        }

        val apfCounterTracker = ApfCounterTracker()
        apfCounterTracker.updateCountersFromData(readProgram())
        Log.i(TAG, "counter map: ${apfCounterTracker.counters}")

        assertThat(replyPayload).isEqualTo(firstByte)
    }
}

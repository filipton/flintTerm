package dev.flint.term.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The parser against three machines' worth of output.
 *
 * Provenance, since it matters for what these tests actually prove:
 *
 *  - [DEBIAN] was captured by running the real script on a real Linux machine
 *    (the build host: kernel 7.1, GNU coreutils, procps). /proc, `df -P` and
 *    `ps -eo … --sort=-pcpu` are the kernel's and coreutils' formats, printed
 *    byte for byte the same on Debian. It is trimmed to four cores and five
 *    interfaces so the numbers can be checked by hand, and the hostname was
 *    replaced; every figure below is otherwise as the machine reported it.
 *  - [ALPINE] was written by hand from busybox's documented output. busybox has
 *    neither `--sort` nor `-Ao`, so its `ps` arrives unsorted and its `df`
 *    column widths differ — which is the whole reason it is here.
 *  - [MACOS] was written by hand from the documented formats of `vm_stat`,
 *    `sysctl vm.swapusage`, `sysctl kern.boottime`, `netstat -ibn` and BSD
 *    `df -P -k`. No Mac was available to capture it from.
 *  - [FREEBSD] likewise, from the documented `vm.stats.vm.*` sysctls and
 *    FreeBSD's own `netstat -ibn`, which carries a column macOS does not.
 */
class ServerStatusParserTest {

    // ---- Debian / a real Linux machine ------------------------------------

    @Test
    fun `a Linux machine reports every field it has`() {
        val s = ServerProbe.parse(DEBIAN)

        assertEquals("Linux", s.os)
        assertEquals("debian-box", s.hostname)
        assertEquals(12, s.cpuCount)
        assertEquals(126966L, s.uptimeSeconds)
        assertEquals(LoadAverage(4.86, 6.31, 4.73), s.load)
    }

    /** The busy share is time not spent idle, and iowait counts as idle. */
    @Test
    fun `CPU comes from the difference between the two proc stat samples`() {
        val s = ServerProbe.parse(DEBIAN)

        assertEquals(0.2325, s.cpu!!, 0.0001)
        assertEquals(4, s.cores.size)
        assertEquals(0.4343, s.cores[0], 0.0001)
        assertEquals(0.0825, s.cores[1], 0.0001)
        assertEquals(0.0784, s.cores[2], 0.0001)
        assertEquals(0.0980, s.cores[3], 0.0001)
    }

    @Test
    fun `used memory is what the kernel calls unavailable`() {
        val m = ServerProbe.parse(DEBIAN).memory!!

        assertEquals(16_289_728L * 1024, m.totalBytes)
        // MemTotal - MemAvailable, not MemTotal - MemFree: the cache is not "used".
        assertEquals((16_289_728L - 4_417_132L) * 1024, m.usedBytes)
        assertEquals((4_181_084L + 67_172L + 254_084L) * 1024, m.cachedBytes)
    }

    @Test
    fun `swap is total minus free`() {
        val swap = ServerProbe.parse(DEBIAN).swap!!

        assertEquals(54_525_944L * 1024, swap.totalBytes)
        assertEquals((54_525_944L - 43_525_864L) * 1024, swap.usedBytes)
    }

    @Test
    fun `only the disks somebody could fill get a row`() {
        val disks = ServerProbe.parse(DEBIAN).disks

        assertEquals(listOf("/", "/boot"), disks.map { it.mount })
        assertEquals(974_612_312L * 1024, disks[0].totalBytes)
        assertEquals(822_808_124L * 1024, disks[0].usedBytes)
        assertEquals(0.844, disks[0].fraction, 0.001)
    }

    @Test
    fun `network rates are the counter difference over the sampled second`() {
        val s = ServerProbe.parse(DEBIAN)

        val busiest = s.busiestInterface!!
        assertEquals("enp5s0", busiest.name)
        assertEquals(27_359_341_226L - 27_359_328_371L, busiest.rxPerSecond)
        assertEquals(34_713_676_685L - 34_713_508_964L, busiest.txPerSecond)
        assertTrue("loopback is not traffic: ${s.interfaces}", s.interfaces.none { it.name == "lo" })
    }

    @Test
    fun `top processes keep the command even when it has a space in it`() {
        val top = ServerProbe.parse(DEBIAN).processes

        assertEquals(10, top.size)
        assertEquals(149412, top[0].pid)
        assertEquals(80.2, top[0].cpuPercent!!, 0.001)
        assertEquals(3.3, top[0].memPercent!!, 0.001)
        assertEquals("zig", top[0].command)
        assertTrue("sorted by CPU", top.zipWithNext().all { (a, b) -> a.cpuPercent!! >= b.cpuPercent!! })
        assertTrue("a command with a space survives", top.any { it.command == "tmux: server" })
    }

    /** This machine has no thermal zone 0, and a temperature of zero would be a lie. */
    @Test
    fun `a missing thermal zone shows nothing rather than zero`() {
        assertNull(ServerProbe.parse(DEBIAN).temperatureC)
    }

    // ---- Alpine / busybox --------------------------------------------------

    @Test
    fun `busybox df columns line up differently and still parse`() {
        val disks = ServerProbe.parse(ALPINE).disks

        assertEquals(listOf("/", "/boot"), disks.map { it.mount })
        assertEquals(8_154_588L * 1024, disks[0].totalBytes)
        assertEquals(1_123_456L * 1024, disks[0].usedBytes)
    }

    /** busybox has no `--sort`, so the ordering has to be ours. */
    @Test
    fun `an unsorted process list is sorted on this side`() {
        val top = ServerProbe.parse(ALPINE).processes

        assertEquals("nginx", top[0].command)
        assertEquals(12.3, top[0].cpuPercent!!, 0.001)
        assertTrue(top.zipWithNext().all { (a, b) -> a.cpuPercent!! >= b.cpuPercent!! })
    }

    @Test
    fun `a machine with no swap reports none, not an empty bar`() {
        val s = ServerProbe.parse(ALPINE)

        assertNull(s.swap)
        assertNotNull(s.memory)
    }

    @Test
    fun `a thermal zone reads as millidegrees`() {
        assertEquals(48.312, ServerProbe.parse(ALPINE).temperatureC!!, 0.0001)
    }

    // ---- macOS -------------------------------------------------------------

    @Test
    fun `macOS load and uptime come from sysctl`() {
        val s = ServerProbe.parse(MACOS)

        assertEquals("Darwin", s.os)
        assertEquals(10, s.cpuCount)
        assertEquals(LoadAverage(2.31, 2.05, 1.88), s.load)
        // now - kern.boottime, both read by the same script run.
        assertEquals(295_000L, s.uptimeSeconds)
    }

    @Test
    fun `macOS memory is the vm_stat page ledger against hw memsize`() {
        val m = ServerProbe.parse(MACOS).memory!!

        assertEquals(34_359_738_368L, m.totalBytes)
        assertEquals(491_520L * 16_384, m.cachedBytes)
        // Total, less free and speculative pages, less the file cache.
        assertEquals(34_359_738_368L - (45_120L + 20_480L) * 16_384 - 491_520L * 16_384, m.usedBytes)
    }

    @Test
    fun `macOS swap comes out of vm swapusage`() {
        val swap = ServerProbe.parse(MACOS).swap!!

        assertEquals(3072L shl 20, swap.totalBytes)
        assertEquals((1234.5 * (1L shl 20)).toLong(), swap.usedBytes)
    }

    /**
     * `netstat -ib` puts the MAC in a column that loopback simply does not
     * have, so the byte totals cannot be found by counting from the left.
     */
    @Test
    fun `netstat byte counters are found past the link column`() {
        val busiest = ServerProbe.parse(MACOS).busiestInterface!!

        assertEquals("en0", busiest.name)
        assertEquals(204_800L, busiest.rxPerSecond)
        assertEquals(40_960L, busiest.txPerSecond)
    }

    @Test
    fun `the system helper volumes are left off the disk list`() {
        val disks = ServerProbe.parse(MACOS).disks

        assertEquals(listOf("/", "/System/Volumes/Data"), disks.map { it.mount })
    }

    @Test
    fun `macOS says nothing about per-core time, so neither do we`() {
        val s = ServerProbe.parse(MACOS)

        assertNull(s.cpu)
        assertTrue(s.cores.isEmpty())
    }

    @Test
    fun `a full path in COMM is kept whole`() {
        val top = ServerProbe.parse(MACOS).processes

        assertEquals(452, top[0].pid)
        assertEquals("/Applications/Firefox.app/Contents/MacOS/firefox", top[0].command)
    }

    // ---- FreeBSD -----------------------------------------------------------

    /**
     * FreeBSD has no `vm_stat` and no `vm.swapusage`, so it goes the other way
     * round the BSD branch: page counts out of `sysctl`, and no swap reading
     * at all rather than a made-up one.
     */
    @Test
    fun `FreeBSD memory comes from the vm stats sysctls`() {
        val s = ServerProbe.parse(FREEBSD)

        val m = s.memory!!
        assertEquals(8_589_934_592L, m.totalBytes)
        assertEquals(300_000L * 4096, m.cachedBytes)
        assertEquals(8_589_934_592L - 120_000L * 4096 - 300_000L * 4096, m.usedBytes)
        assertNull(s.swap)
        assertEquals(LoadAverage(0.42, 0.38, 0.31), s.load)
    }

    /** FreeBSD's `netstat` has an `Idrop` column that macOS does not. */
    @Test
    fun `an extra netstat column does not shift the byte counters`() {
        val busiest = ServerProbe.parse(FREEBSD).busiestInterface!!

        assertEquals("em0", busiest.name)
        assertEquals(102_400L, busiest.rxPerSecond)
        assertEquals(20_480L, busiest.txPerSecond)
    }

    // ---- the shape of the thing --------------------------------------------

    @Test
    fun `nothing at all parses to nothing at all, not to zeroes`() {
        val s = ServerProbe.parse("")

        assertNull(s.os)
        assertNull(s.load)
        assertNull(s.cpu)
        assertNull(s.memory)
        assertNull(s.uptimeSeconds)
        assertTrue(s.disks.isEmpty())
        assertTrue(s.processes.isEmpty())
        assertTrue(s.interfaces.isEmpty())
    }

    /** Whatever a chatty profile printed before the first marker is not data. */
    @Test
    fun `noise ahead of the first marker is discarded`() {
        val s = ServerProbe.parse("Welcome to Ubuntu 24.04 LTS\nLast login: Mon Sep  8\n" + DEBIAN)

        assertEquals("Linux", s.os)
        assertEquals("debian-box", s.hostname)
    }

    /** A half-finished reply is missing sections, not full of wrong numbers. */
    @Test
    fun `output cut off mid-stream gives up the rest rather than guessing`() {
        val s = ServerProbe.parse(DEBIAN.substringBefore("@@stat2"))

        assertEquals("Linux", s.os)
        assertNotNull(s.memory)
        assertNull("one sample is not a rate", s.cpu)
        assertTrue(s.interfaces.isEmpty())
    }

    @Test
    fun `the script reads what the plan says it reads`() {
        val script = ServerProbe.SCRIPT

        listOf(
            "/proc/loadavg", "/proc/meminfo", "/proc/stat", "/proc/uptime", "/proc/net/dev",
            "/sys/class/thermal/thermal_zone0/temp", "nproc", "hostname", "df -P",
            "ps -eo pid,pcpu,pmem,comm --sort=-pcpu", "vm_stat", "sysctl", "uname -s",
        ).forEach { assertTrue("script is missing $it", script.contains(it)) }
        // One sleep on each branch: a rate needs two samples a known distance apart.
        assertEquals(2, Regex("^\\s*sleep 1$", RegexOption.MULTILINE).findAll(script).count())
    }
}

/**
 * Captured from a real Linux machine, trimmed to four cores and five
 * interfaces; only the hostname was replaced.
 */
private val DEBIAN = """
@@os
Linux
@@hostname
debian-box
@@nproc
12
@@loadavg
4.86 6.31 4.73 3/1675 152151
@@meminfo
MemTotal:       16289728 kB
MemFree:          497516 kB
MemAvailable:    4417132 kB
Buffers:           67172 kB
Cached:          4181084 kB
SwapCached:      1191264 kB
Active:          8530104 kB
Inactive:        4139024 kB
SwapTotal:      54525944 kB
SwapFree:       43525864 kB
SReclaimable:     254084 kB
@@stat1
cpu  24045764 2057571 4858231 98537741 20489094 1043240 308290 0 92431 966616
cpu0 2060247 164086 422099 7824189 1929178 70855 85659 0 8240 79688
cpu1 1687687 166224 419425 8434015 1707178 92295 76472 0 6692 85014
cpu2 2155897 170105 430974 7888471 1841945 68614 22084 0 9276 84311
cpu3 1184082 147944 319893 9230465 1645550 60448 15559 0 3366 65178
ctxt 4433129144
btime 1788781187
processes 4345927
@@net1
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 20401748563 5256272    0    0    0     0          0         0 20401748563 5256272    0    0    0     0       0          0
enp5s0: 27359328371 32495168    0 9791    0     0          0    159851 34713508964 10800474    0    0    0     0       0          0
virbr0: 6363271534  488603    0    0    0     0          0       322 43415247  531860    0 6553    0     0       0          0
docker0: 8474735  139190    0    0    0     0          0         0 1799513425  168980    0    1    0     0       0          0
veth10f7d3e:     126       3    0    0    0     0          0         0  2180084   11056    0    0    0     0       0          0
@@stat2
cpu  24045997 2057583 4858253 98538490 20489279 1043246 308293 0 92431 966623
cpu0 2060290 164086 422099 7824245 1929178 70855 85659 0 8240 79688
cpu1 1687692 166225 419427 8434073 1707209 92295 76472 0 6692 85014
cpu2 2155902 170106 430975 7888564 1841946 68614 22084 0 9276 84312
cpu3 1184087 147945 319895 9230555 1645552 60449 15559 0 3366 65179
ctxt 4433146051
btime 1788781187
processes 4345938
@@net2
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo: 20401749933 5256281    0    0    0     0          0         0 20401749933 5256281    0    0    0     0       0          0
enp5s0: 27359341226 32495275    0 9791    0     0          0    159851 34713676685 10800544    0    0    0     0       0          0
virbr0: 6363271534  488603    0    0    0     0          0       322 43415247  531860    0 6553    0     0       0          0
docker0: 8474735  139190    0    0    0     0          0         0 1799513425  168980    0    1    0     0       0          0
veth10f7d3e:     126       3    0    0    0     0          0         0  2180084   11056    0    0    0     0       0          0
@@uptime
126966.50 985384.91
@@thermal
@@df
Filesystem     1024-blocks      Used Available Capacity Mounted on
/dev/nvme0n1p3   974612312 822808124 109952016      89% /
devtmpfs           8036956         0   8036956       0% /dev
tmpfs              8144864     89936   8054928       2% /dev/shm
efivarfs               128        34        90      28% /sys/firmware/efi/efivars
tmpfs              3257948      1836   3256112       1% /run
none                  1024         0      1024       0% /run/credentials/systemd-journald.service
tmpfs              8144864   2830852   5314012      35% /tmp
/dev/nvme0n1p1      523248    187236    336012      36% /boot
tmpfs              1628972       132   1628840       1% /run/user/1000
@@ps
    PID %CPU %MEM COMMAND
 149412 80.2  3.3 zig
 110596 79.4 17.8 qemu-system-x86
 149411 77.0  3.2 zig
 122111 15.9  8.3 java
3551613 15.7  0.4 ghostty
 148616 10.4  0.8 zig
1756703  9.2  9.7 qemu-system-x86
4099474  5.6  2.7 claude
 148727  4.6  0.1 build
   2750  1.6  0.0 tmux: server
@@end
""".trimIndent()

/**
 * Written by hand from busybox's documented output: an Alpine VM with no swap,
 * a thermal zone, and a `ps` that neither sorts nor takes `-A`.
 */
private val ALPINE = """
@@os
Linux
@@hostname
alpine
@@nproc
2
@@loadavg
0.15 0.09 0.03 1/98 3412
@@meminfo
MemTotal:        2035212 kB
MemFree:         1512340 kB
MemAvailable:    1804556 kB
Buffers:           12488 kB
Cached:           298120 kB
SwapCached:            0 kB
SwapTotal:             0 kB
SwapFree:              0 kB
SReclaimable:      18044 kB
@@stat1
cpu  120400 1200 45300 8912000 3400 0 900 0 0 0
cpu0 60100 600 22500 4456200 1700 0 450 0 0 0
cpu1 60300 600 22800 4455800 1700 0 450 0 0 0
@@net1
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    4096      40    0    0    0     0          0         0     4096      40    0    0    0     0       0          0
  eth0: 88123456  120400    0    0    0     0          0        12 12045600   90300    0    0    0     0       0          0
@@stat2
cpu  120450 1200 45320 8912430 3400 0 900 0 0 0
cpu0 60125 600 22510 4456415 1700 0 450 0 0 0
cpu1 60325 600 22810 4456015 1700 0 450 0 0 0
@@net2
Inter-|   Receive                                                |  Transmit
 face |bytes    packets errs drop fifo frame compressed multicast|bytes    packets errs drop fifo colls carrier compressed
    lo:    4096      40    0    0    0     0          0         0     4096      40    0    0    0     0       0          0
  eth0: 88135456  120520    0    0    0     0          0        12 12048900   90380    0    0    0     0       0          0
@@uptime
89124.30 176300.12
@@thermal
48312
@@df
Filesystem           1024-blocks      Used Available Capacity Mounted on
/dev/vda3               8154588   1123456   6614884      15% /
devtmpfs                  10240         0     10240       0% /dev
shm                      255420         0    255420       0% /dev/shm
tmpfs                    255420       112    255308       1% /run
/dev/vda1                262144     14336    247808       5% /boot
@@ps
  PID %CPU %MEM COMMAND
    1  0.0  0.1 init
  412  0.1  0.4 sshd
  980 12.3  2.1 nginx
  981  4.7  1.9 nginx
 1204  0.9  6.8 postgres
 1330  2.2  0.3 crond
@@end
""".trimIndent()

/**
 * Written by hand from the documented formats of `vm_stat`, `sysctl` and
 * BSD `netstat`/`df`. No Mac was available to capture this from.
 */
private val MACOS = """
@@os
Darwin
@@hostname
studio.local
@@nproc
10
@@loadavg
{ 2.31 2.05 1.88 }
@@uptimecmd
14:03  up 3 days,  4:21, 3 users, load averages: 2.31 2.05 1.88
@@boottime
{ sec = 1757000000, usec = 512345 } Wed Sep  4 09:33:20 2025
@@now
1757295000
@@memtotal
34359738368
@@vmstat
Mach Virtual Memory Statistics: (page size of 16384 bytes)
Pages free:                               45120.
Pages active:                            733120.
Pages inactive:                          701440.
Pages speculative:                        20480.
Pages throttled:                              0.
Pages wired down:                        312320.
Pages purgeable:                            8192.
"Translation faults":                 1234567890.
Pages copy-on-write:                    12345678.
Pages zero filled:                     987654321.
Pages reactivated:                      23456789.
File-backed pages:                        491520.
Anonymous pages:                          963520.
Pages stored in compressor:               234567.
Pages occupied by compressor:             123456.
@@vmsysctl
hw.pagesize: 16384
@@swap
total = 3072.00M  used = 1234.50M  free = 1837.50M  (encrypted)
@@net1
Name  Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
lo0   16384 <Link#1>                        168281     0   38792704   168281     0   38792704     0
en0   1500  <Link#4>    3c:22:fb:11:22:33  9912345     0 8123456789  7712345     0 1234567890     0
en0   1500  192.168.1     192.168.1.42     9912345     -  8123456789  7712345     - 1234567890     -
@@net2
Name  Mtu   Network       Address            Ipkts Ierrs     Ibytes    Opkts Oerrs     Obytes  Coll
lo0   16384 <Link#1>                        168290     0   38794112   168290     0   38794112     0
en0   1500  <Link#4>    3c:22:fb:11:22:33  9912560     0 8123661589  7712480     0 1234608850     0
en0   1500  192.168.1     192.168.1.42     9912560     -  8123661589  7712480     - 1234608850     -
@@df
Filesystem     1024-blocks      Used Available Capacity  Mounted on
/dev/disk3s1s1   971350180  10123456 400123456       3%  /
devfs                  200       200         0     100%  /dev
/dev/disk3s5     971350180 550123456 400123456      58%  /System/Volumes/Data
/dev/disk3s4     971350180   2097152 400123456       1%  /System/Volumes/VM
map auto_home            0         0         0     100%  /System/Volumes/Data/home
@@ps
  PID %CPU %MEM COMM
  452  32.1  4.2 /Applications/Firefox.app/Contents/MacOS/firefox
  318  11.4  2.8 /System/Library/CoreServices/WindowServer
  980   6.0  1.1 /usr/sbin/mDNSResponder
    1   0.2  0.1 /sbin/launchd
@@end
""".trimIndent()

/**
 * Written by hand from FreeBSD's documented `sysctl vm.stats.vm.*`, `netstat
 * -ibn` and `df -P -k`. No FreeBSD machine was available to capture it from.
 */
private val FREEBSD = """
@@os
FreeBSD
@@hostname
bsd.example
@@nproc
4
@@loadavg
{ 0.42 0.38 0.31 }
@@uptimecmd
 2:15PM  up 12 days,  3:04, 1 user, load averages: 0.42, 0.38, 0.31
@@boottime
{ sec = 1756000000, usec = 0 } Sat Aug 23 22:26:40 2025
@@now
1757040000
@@memtotal
8589934592
@@vmstat
@@vmsysctl
hw.pagesize: 4096
vm.stats.vm.v_page_count: 2045000
vm.stats.vm.v_free_count: 120000
vm.stats.vm.v_inactive_count: 300000
vm.stats.vm.v_cache_count: 0
@@swap
@@net1
Name    Mtu Network       Address              Ipkts Ierrs Idrop     Ibytes    Opkts Oerrs     Obytes  Coll
em0    1500 <Link#1>      08:00:27:aa:bb:cc  1234567     0     0  987654321   765432     0  123456789     0
lo0   16384 <Link#2>                            9012     0     0    1024000     9012     0    1024000     0
@@net2
Name    Mtu Network       Address              Ipkts Ierrs Idrop     Ibytes    Opkts Oerrs     Obytes  Coll
em0    1500 <Link#1>      08:00:27:aa:bb:cc  1234690     0     0  987756721   765510     0  123477269     0
lo0   16384 <Link#2>                            9012     0     0    1024000     9012     0    1024000     0
@@df
Filesystem     1024-blocks     Used    Avail Capacity  Mounted on
/dev/gpt/rootfs   20307968  4123456 14559876      22%  /
devfs                    1        1        0     100%  /dev
@@ps
  PID %CPU %MEM COMMAND
  912  5.4  1.2 bhyve
  455  0.4  0.2 sshd
    1  0.0  0.1 init
@@end
""".trimIndent()

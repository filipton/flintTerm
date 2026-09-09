// Android forbids apps from listing network interfaces over netlink, which
// Go's net.Interfaces() (and therefore tsnet) relies on. The app enumerates
// interfaces with the Java API and hands them over as text; this file plugs
// that into tailscale's netmon, the same way the official Android app does.
//
// Line format (one interface per line):
//   name|index|mtu|up,broadcast,loopback,pointtopoint,multicast|addr/prefix addr/prefix ...

package main

import "C"

import (
	"errors"
	"net"
	"os"
	"strconv"
	"strings"
	"sync"

	"tailscale.com/net/netmon"
)

var (
	ifaceMu   sync.Mutex
	ifaceSpec string
)

func init() {
	netmon.RegisterInterfaceGetter(androidInterfaces)
}

//export TsnetSetInterfaces
func TsnetSetInterfaces(spec *C.char) C.int {
	ifaceMu.Lock()
	ifaceSpec = C.GoString(spec)
	ifaceMu.Unlock()
	return 0
}

func androidInterfaces() ([]netmon.Interface, error) {
	ifaceMu.Lock()
	spec := ifaceSpec
	ifaceMu.Unlock()
	if strings.TrimSpace(spec) == "" {
		return nil, errors.New("no interface list from the app yet")
	}
	var out []netmon.Interface
	for _, line := range strings.Split(spec, "\n") {
		f := strings.Split(strings.TrimSpace(line), "|")
		if len(f) < 5 || f[0] == "" {
			continue
		}
		idx, _ := strconv.Atoi(f[1])
		mtu, _ := strconv.Atoi(f[2])
		var flags net.Flags
		bits := strings.Split(f[3], ",")
		set := func(i int, fl net.Flags) {
			if i < len(bits) && bits[i] == "1" {
				flags |= fl
			}
		}
		set(0, net.FlagUp)
		set(1, net.FlagBroadcast)
		set(2, net.FlagLoopback)
		set(3, net.FlagPointToPoint)
		set(4, net.FlagMulticast)
		var addrs []net.Addr
		for _, a := range strings.Fields(f[4]) {
			ip, ipnet, err := net.ParseCIDR(a)
			if err != nil {
				continue
			}
			addrs = append(addrs, &net.IPNet{IP: ip, Mask: ipnet.Mask})
		}
		if addrs == nil {
			addrs = []net.Addr{}
		}
		out = append(out, netmon.Interface{
			Interface: &net.Interface{Index: idx, MTU: mtu, Name: f[0], Flags: flags},
			AltAddrs:  addrs,
		})
	}
	return out, nil
}

//export TsnetSetLogsDir
// Tailscale's logpolicy panics when it cannot find a writable place for its
// log state; on Android nothing in its search list exists, so point it at the
// app's private directory (Go keeps its own copy of the environment, so this
// must be done from Go).
func TsnetSetLogsDir(dir *C.char) C.int {
	d := C.GoString(dir)
	if err := os.MkdirAll(d, 0o700); err != nil {
		return 1
	}
	os.Setenv("TS_LOGS_DIR", d)
	if os.Getenv("HOME") == "" {
		os.Setenv("HOME", d)
	}
	return 0
}

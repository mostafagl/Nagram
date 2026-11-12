package internet

import (
    "testing"
    "github.com/xtls/xray-core/common/net"
)

type testDirectBinder struct { bindCount int; allowed bool; resolved string }
func (b *testDirectBinder) BindSocket(fd int64) bool { b.bindCount++; return b.allowed }
func (b *testDirectBinder) ResolveHost(host string) string { return b.resolved }

func TestParvaDirectBindingIsolationAndRemoval(t *testing.T) {
    one := &testDirectBinder{allowed: true, resolved: "192.0.2.1"}
    two := &testDirectBinder{allowed: false, resolved: "192.0.2.2"}
    cleanup := RegisterParvaDirectBinding("parva-direct:one", one)
    defer cleanup()
    cleanup2 := RegisterParvaDirectBinding("parva-direct:two", two)
    defer cleanup2()
    if err := bindParvaDirectSocket("parva-direct:one", 1); err != nil { t.Fatal(err) }
    if err := bindParvaDirectSocket("parva-direct:two", 2); err == nil { t.Fatal("denial must fail") }
    if one.bindCount != 1 || two.bindCount != 1 { t.Fatal("bindings mixed") }
    dest := net.TCPDestination(net.DomainAddress("example.com"), net.Port(443))
    direct, err := prepareParvaDirectDestination(&SocketConfig{Interface: "parva-direct:one"}, dest)
    if err != nil || direct.Address.String() != "192.0.2.1" { t.Fatal("direct DNS not used", err) }
    ordinary, err := prepareParvaDirectDestination(nil, dest)
    if err != nil || ordinary.Address != dest.Address { t.Fatal("normal core changed") }
    cleanup()
    if err := bindParvaDirectSocket("parva-direct:one", 1); err == nil { t.Fatal("expired binding must fail") }
}

func TestParvaDirectDNSNeverFallsBack(t *testing.T) {
    binder := &testDirectBinder{allowed: true}
    cleanup := RegisterParvaDirectBinding("parva-direct:empty", binder)
    defer cleanup()
    dest := net.TCPDestination(net.DomainAddress("example.com"), net.Port(443))
    if _, err := prepareParvaDirectDestination(&SocketConfig{Interface: "parva-direct:empty"}, dest); err == nil {
        t.Fatal("empty direct DNS must not use VPN/default DNS")
    }
}

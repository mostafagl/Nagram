package internet

import (
    "errors"
    "strings"
    "sync"

    "github.com/xtls/xray-core/common/net"
)

const directBindingPrefix = "parva-direct:"

// A binding is registered only for the lifetime of a single isolated delay test.
// Ordinary cores have no such interface token and never consult this registry.
type ParvaDirectBinder interface {
    BindSocket(fd int64) bool
    ResolveHost(host string) string
}

var directBindings = struct {
    sync.RWMutex
    values map[string]ParvaDirectBinder
}{values: make(map[string]ParvaDirectBinder)}

func RegisterParvaDirectBinding(token string, binder ParvaDirectBinder) func() {
    directBindings.Lock()
    directBindings.values[token] = binder
    directBindings.Unlock()
    return func() {
        directBindings.Lock()
        delete(directBindings.values, token)
        directBindings.Unlock()
    }
}

func directBinder(token string) (ParvaDirectBinder, error) {
    directBindings.RLock()
    binder := directBindings.values[token]
    directBindings.RUnlock()
    if binder == nil { return nil, errors.New("direct network binding expired") }
    return binder, nil
}

func prepareParvaDirectDestination(config *SocketConfig, dest net.Destination) (net.Destination, error) {
    if config == nil || !strings.HasPrefix(config.Interface, directBindingPrefix) { return dest, nil }
    binder, err := directBinder(config.Interface)
    if err != nil { return dest, err }
    if dest.Address.Family().IsDomain() {
        ip := net.ParseAddress(binder.ResolveHost(dest.Address.Domain()))
        if !ip.Family().IsIP() { return dest, errors.New("direct network DNS unavailable") }
        dest.Address = ip
    }
    return dest, nil
}

func bindParvaDirectSocket(token string, fd uintptr) error {
    binder, err := directBinder(token)
    if err != nil { return err }
    if !binder.BindSocket(int64(fd)) { return errors.New("direct socket binding denied") }
    return nil
}

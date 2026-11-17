package libv2ray

import (
    "crypto/rand"
    "encoding/hex"
    "encoding/json"
    "errors"

    "github.com/xtls/xray-core/transport/internet"
)

// DirectSocketBinder binds ONLY sockets belonging to an isolated delay-test core.
// No global dialer or process network is replaced.
type DirectSocketBinder interface {
    BindSocket(fd int64) bool
    ResolveHost(host string) string
}

func MeasureOutboundDelayOnNetwork(configJSON string, url string, binder DirectSocketBinder) (int64, error) {
    if binder == nil { return -1, errors.New("missing direct binder") }
    var random [16]byte
    if _, err := rand.Read(random[:]); err != nil { return -1, err }
    token := "parva-direct:" + hex.EncodeToString(random[:])
    remove := internet.RegisterParvaDirectBinding(token, binder)
    defer remove()
    var config map[string]interface{}
    if err := json.Unmarshal([]byte(configJSON), &config); err != nil { return -1, err }
    outbounds, ok := config["outbounds"].([]interface{})
    if !ok || len(outbounds) == 0 { return -1, errors.New("missing outbound") }
    for _, value := range outbounds {
        outbound, ok := value.(map[string]interface{})
        if !ok { return -1, errors.New("invalid outbound") }
        stream, ok := outbound["streamSettings"].(map[string]interface{})
        if !ok { stream = make(map[string]interface{}); outbound["streamSettings"] = stream }
        sockopt, ok := stream["sockopt"].(map[string]interface{})
        if !ok { sockopt = make(map[string]interface{}); stream["sockopt"] = sockopt }
        sockopt["interface"] = token
    }
    encoded, err := json.Marshal(config)
    if err != nil { return -1, err }
    return MeasureOutboundDelay(string(encoded), url)
}

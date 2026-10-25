#!/bin/bash
# Builds TMessagesProj/libs/libv2ray.aar (Xray-core bindings) from AndroidLibXrayLite.
# Requires Go and the Android NDK. Usage: ./run libs v2ray

set -e

source "bin/init/env.sh"

LIB_REPO=https://github.com/2dust/AndroidLibXrayLite
LIB_COMMIT=76a6e2172210613fcb4aa32b6de55bcd9452ce43
MOBILE_VERSION=v0.0.0-20261011034528-197770444867
OUT="$PROJECT/TMessagesProj/libs/libv2ray.aar"

if [ -n "$NATIVE_TARGET" ]; then
  case "$NATIVE_TARGET" in
    armeabi-v7a) TARGETS=android/arm ;;
    arm64-v8a) TARGETS=android/arm64 ;;
    x86) TARGETS=android/386 ;;
    x86_64) TARGETS=android/amd64 ;;
    *) echo "Unknown NATIVE_TARGET $NATIVE_TARGET"; exit 1 ;;
  esac
else
  TARGETS=android/arm,android/arm64
fi

export GOTOOLCHAIN=auto
go install golang.org/x/mobile/cmd/gomobile@$MOBILE_VERSION golang.org/x/mobile/cmd/gobind@$MOBILE_VERSION
export PATH=$PATH:$(go env GOPATH)/bin

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

git -C "$WORK" init -q src
git -C "$WORK/src" fetch -q --depth 1 "$LIB_REPO" "$LIB_COMMIT"
git -C "$WORK/src" checkout -q FETCH_HEAD

cd "$WORK/src"
# geoip/geosite databases are only needed for routing rules, which the generated configs don't use.
rm -rf assets
gomobile init
gomobile bind -v -target="$TARGETS" -androidapi 23 -trimpath -ldflags='-s -w -buildid= -checklinkname=0' -o libv2ray.aar ./

mkdir -p "$(dirname "$OUT")"
cp libv2ray.aar "$OUT"
echo ">> $OUT"

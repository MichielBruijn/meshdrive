#!/bin/sh
# Builds libs/yggdrasil-go (git submodule) into app/libs/yggdrasil.aar via the
# submodule's own contrib/mobile/build script - see YggdrasilVpnService's doc
# for how the app uses the result.
#
# Needs on PATH/env: go >=1.24 (yggdrasil-go's own go.mod requirement), gomobile
# + gobind (golang.org/x/mobile/cmd/{gomobile,gobind}), ANDROID_HOME and
# ANDROID_NDK_HOME pointing at an installed NDK.
#
# arm64-v8a only, deliberately not a universal build: this app isn't distributed
# via an app store, and every real test device is arm64. Re-run this script
# (adding more -target entries below) if that ever changes.
set -e

cd "$(dirname "$0")/../libs/yggdrasil-go"

# Pinned, not @latest: golang.org/x/mobile has no tagged releases (always
# consumed via commit pseudo-versions) and its HEAD has outpaced what the
# locally installed Go toolchain can build more than once already during this
# investigation. This is the same commit used to build gomobile/gobind
# themselves - known compatible with Go 1.24.
go get golang.org/x/mobile/bind@cd096645fcd3325d4006c702e6514a9f6b13b94a
gomobile bind \
    -target=android/arm64 -androidapi 26 -tags mobile \
    -ldflags="-s -w -checklinkname=0" \
    -o ../../app/libs/yggdrasil.aar \
    ./contrib/mobile ./src/config

echo "Built app/libs/yggdrasil.aar:"
ls -la ../../app/libs/yggdrasil.aar

#!/bin/sh
# Run on a Mac with Xcode, a supported JDK, and an installed iPad simulator.
set -eu
cd "$(dirname "$0")/.."
if [ -z "${JAVA_HOME:-}" ]; then export JAVA_HOME="$(/usr/libexec/java_home -v 17)"; fi
DEVICE_ID="${DOTNOTE_SIMULATOR_ID:-$(xcrun simctl list devices available -j | python3 -c 'import json,sys; d=json.load(sys.stdin); print(next(v["udid"] for a in d["devices"].values() for v in a if "iPad" in v["name"]))')}"
./gradlew :shared:jvmTest :shared:iosSimulatorArm64Test
xcodebuild -project ios/Dotnote.xcodeproj -scheme Dotnote \
  -configuration Debug -destination "platform=iOS Simulator,id=$DEVICE_ID" \
  -derivedDataPath ios/build CODE_SIGNING_ALLOWED=NO test

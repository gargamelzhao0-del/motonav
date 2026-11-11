#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
# Works with a JDK exposing jdk.compiler even where no javac launcher is installed.
java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/Simulation.java app/src/main/java/com/motonav/companion/Protocol.java app/src/main/java/com/motonav/companion/MapProvider.java app/src/main/java/com/motonav/companion/DemoMapProvider.java app/src/main/java/com/motonav/companion/UnavailableMapProvider.java app/src/main/java/com/motonav/companion/LocationFix.java app/src/main/java/com/motonav/companion/LocationQuality.java app/src/main/java/com/motonav/companion/LocalNetwork.java app/src/main/java/com/motonav/companion/NetworkProtocol.java app/src/main/java/com/motonav/companion/AmapAccess.java app/src/main/java/com/motonav/companion/AmapKeySession.java tests/AmapAccessTests.java tests/AmapKeySessionTests.java tests/CoreTests.java tests/ProviderTests.java tests/LocationTests.java tests/NetworkTests.java tests/SimulationNetworkTests.java
java -cp "$build" CoreTests
java -cp "$build" ProviderTests
java -cp "$build" LocationTests
java -cp "$build" NetworkTests
java -cp "$build" SimulationNetworkTests

java -cp "$build" AmapAccessTests

java -cp "$build" AmapKeySessionTests
java -m jdk.compiler/com.sun.tools.javac.Main -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/MapStartupPolicy.java tests/MapStartupPolicyTests.java
java -cp "$build" MapStartupPolicyTests
python3 tests/activity_contracts.py

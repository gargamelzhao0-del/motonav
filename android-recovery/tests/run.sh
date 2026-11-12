#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/DiagnosticRecord.java app/src/main/java/com/motonav/companion/DiagnosticEndpoint.java tests/DiagnosticTests.java
java -cp "$build" DiagnosticTests "$build/diagnostic.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/DiagnosticSpool.java tests/DiagnosticSpoolTests.java
java -cp "$build" DiagnosticSpoolTests
python3 -B tests/diagnostic_receiver_tests.py "$build/diagnostic.frames"
# Works with a JDK exposing jdk.compiler even where no javac launcher is installed.
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/Simulation.java app/src/main/java/com/motonav/companion/Protocol.java app/src/main/java/com/motonav/companion/MapProvider.java app/src/main/java/com/motonav/companion/DemoMapProvider.java app/src/main/java/com/motonav/companion/UnavailableMapProvider.java app/src/main/java/com/motonav/companion/LocationFix.java app/src/main/java/com/motonav/companion/LocationQuality.java app/src/main/java/com/motonav/companion/LocalNetwork.java app/src/main/java/com/motonav/companion/NetworkProtocol.java app/src/main/java/com/motonav/companion/AmapAccess.java app/src/main/java/com/motonav/companion/AmapKeySession.java tests/AmapAccessTests.java tests/AmapKeySessionTests.java tests/CoreTests.java tests/ProviderTests.java tests/LocationTests.java tests/NetworkTests.java tests/SimulationNetworkTests.java
java -cp "$build" CoreTests
java -cp "$build" ProviderTests
java -cp "$build" LocationTests
java -cp "$build" NetworkTests
java -cp "$build" SimulationNetworkTests

java -cp "$build" AmapAccessTests

java -cp "$build" AmapKeySessionTests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/MapStartupPolicy.java tests/MapStartupPolicyTests.java
java -cp "$build" MapStartupPolicyTests
python3 tests/activity_contracts.py
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/JourneyState.java tests/JourneyStateTests.java
java -cp "$build" JourneyStateTests
python3 -B tests/journey_controller_tests.py

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/MapFixTracker.java tests/MapFixTests.java
java -cp "$build" MapFixTests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/LocationRecovery.java tests/LocationRecoveryTests.java
java -cp "$build" LocationRecoveryTests
python3 -B tests/location_feed_tests.py

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/UpdatePolicy.java app/src/main/java/com/motonav/companion/UpdateTransport.java tests/UpdateTests.java
java -cp "$build" UpdateTests
python3 tests/update_contracts.py
python3 -B tests/update_release_tests.py


java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/NavigationFrame.java app/src/main/java/com/motonav/companion/NavigationProtocol.java tests/NavigationProtocolTests.java
java -cp "$build" NavigationProtocolTests "$build/nav3.frames"
g++ -std=c++17 -Wall -Wextra -Werror ../moto_esp32/tests/navigation_protocol_test.cpp -o "$build/navigation-protocol-test"
"$build/navigation-protocol-test" "$build/nav3.frames"

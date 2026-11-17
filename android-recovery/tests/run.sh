#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
build=$(mktemp -d)
trap 'rm -rf "$build"' EXIT
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -d "$build" app/src/main/java/com/motonav/companion/DiagnosticRecord.java app/src/main/java/com/motonav/companion/DiagnosticEndpoint.java tests/DiagnosticTests.java
java -cp "$build" DiagnosticTests "$build/diagnostic.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/DiagnosticSpool.java tests/DiagnosticSpoolTests.java
java -cp "$build" DiagnosticSpoolTests
python3 -B tests/diagnostics_threading_tests.py
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
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/NaviFixTracker.java tests/NaviFixTests.java
java -cp "$build" NaviFixTests
python3 tests/activity_contracts.py
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/JourneyState.java tests/JourneyStateTests.java
java -cp "$build" JourneyStateTests
python3 -B tests/journey_controller_tests.py

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/MapFixTracker.java tests/MapFixTests.java
java -cp "$build" MapFixTests
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/LocationRecovery.java tests/LocationRecoveryTests.java
java -cp "$build" LocationRecoveryTests
python3 -B tests/location_feed_tests.py
python3 -B tests/ble_link_tests.py

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/UpdatePolicy.java app/src/main/java/com/motonav/companion/UpdateTransport.java tests/UpdateTests.java
java -cp "$build" UpdateTests
python3 tests/update_contracts.py
python3 -B tests/update_release_tests.py


java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/NavigationFrame.java app/src/main/java/com/motonav/companion/NavigationProtocol.java tests/NavigationProtocolTests.java
java -cp "$build" NavigationProtocolTests "$build/nav3.frames"
g++ -std=c++17 -Wall -Wextra -Werror ../moto_esp32/tests/navigation_protocol_test.cpp -o "$build/navigation-protocol-test"
"$build/navigation-protocol-test" "$build/nav3.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/DeviceRoute.java app/src/main/java/com/motonav/companion/Basemap.java app/src/main/java/com/motonav/companion/MapViewport.java app/src/main/java/com/motonav/companion/NavigationDetails.java app/src/main/java/com/motonav/companion/RouteProtocol.java tests/RouteProtocolTests.java
java -cp "$build" RouteProtocolTests "$build/nav4.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" tests/RoutePlanTests.java
java -cp "$build" RoutePlanTests "$build/nav4-plan.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/route_protocol_test.cpp -o "$build/route-protocol-test"
"$build/route-protocol-test" "$build/nav4.frames" "$build/nav4-plan.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/VectorTile.java app/src/main/java/com/motonav/companion/LocalMapLabels.java app/src/main/java/com/motonav/companion/LocalMapBuilder.java app/src/main/java/com/motonav/companion/MapDetailSelector.java app/src/main/java/com/motonav/companion/MapPolygon.java app/src/main/java/com/motonav/companion/MapTileStore.java app/src/main/java/com/motonav/companion/MapQuality.java app/src/main/java/com/motonav/companion/MapRegionLoader.java tests/BasemapTests.java tests/MapGeometryTests.java tests/MapQualityTests.java
java -cp "$build" com.motonav.companion.MapGeometryTests
java -cp "$build" com.motonav.companion.MapQualityTests
java -cp "$build" com.motonav.companion.BasemapTests "$build/nav4-scene.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/basemap_protocol_test.cpp -o "$build/basemap-protocol-test"
"$build/basemap-protocol-test" "$build/nav4-scene.frames" "$build/nav4-map-updates.frames"
java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/BleDiagnosticProtocol.java tests/BleDiagnosticProtocolTests.java
java -cp "$build" BleDiagnosticProtocolTests "$build/diag1.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_diagnostics/include ../moto_esp32/tests/host/diagnostic_protocol_test.cpp ../moto_esp32/components/motonav_diagnostics/format.cpp -o "$build/diagnostic-protocol-test"
"$build/diagnostic-protocol-test" "$build/diag1.frames"

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" tests/NavigationDetailsTests.java
java -cp "$build" NavigationDetailsTests "$build/nav4-details.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/navigation_details_test.cpp -o "$build/navigation-details-test"
"$build/navigation-details-test" "$build/nav4-details.frames"

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" tests/MapPositionTests.java
java -cp "$build" MapPositionTests "$build/nav4-position.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/map_position_test.cpp -o "$build/map-position-test"
"$build/map-position-test" "$build/nav4-position.frames"

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" tests/MapLabelTests.java
java -cp "$build" com.motonav.companion.MapLabelTests "$build/nav4-labels.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/map_labels_test.cpp -o "$build/map-labels-test"
"$build/map-labels-test" "$build/nav4-labels.frames"

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" tests/MapSurfaceTests.java
java -cp "$build" com.motonav.companion.MapSurfaceTests "$build/nav4-surfaces.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/map_surfaces_test.cpp -o "$build/map-surfaces-test"
"$build/map-surfaces-test" "$build/nav4-surfaces.frames"

java -m jdk.compiler/com.sun.tools.javac.Main -encoding UTF-8 -source 17 -target 17 -cp "$build" -d "$build" app/src/main/java/com/motonav/companion/NearbyPlace.java app/src/main/java/com/motonav/companion/NearbyPlacesState.java app/src/main/java/com/motonav/companion/MapSceneComposer.java tests/NearbyPlaceTests.java
java -cp "$build" com.motonav.companion.NearbyPlaceTests "$build/nav4-label-sources.frames"
g++ -std=c++17 -Wall -Wextra -Werror -I../moto_esp32/components/motonav_core/include ../moto_esp32/tests/host/map_label_sources_test.cpp -o "$build/map-label-sources-test"
"$build/map-label-sources-test" "$build/nav4-label-sources.frames"
python3 -B tests/nearby_places_loader_tests.py

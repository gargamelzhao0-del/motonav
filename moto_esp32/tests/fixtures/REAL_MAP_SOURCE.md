The `nav4-real-map.frames` visual test uses a bounded extract of real OpenStreetMap
streets, buildings, water and parks around central London. The route is a selected
street segment and all guidance/position samples are **test inputs**, not a recorded
journey. This fixture must never be sent as real phone navigation.

Source: https://vector.openstreetmap.org/shortbread_v1/14/8186/5448.mvt
Retrieved 2026-11-16. Original tile SHA256:
`ab9c6c7141397dcdf316eb140a576716515c8f60618ab6c8b1d59bb5549ee85f`.

Map data © OpenStreetMap contributors, available under the Open Database License:
https://www.openstreetmap.org/copyright and https://opendatacommons.org/licenses/odbl/1-0/.
The derived map geometry in this fixture is provided under the same database license.
`BasemapTests` can regenerate the extract from a locally cached copy of this tile;
ordinary CI is offline and uses the checked-in extract. No bulk map download.

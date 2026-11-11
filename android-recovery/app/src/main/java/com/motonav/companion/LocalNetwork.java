package com.motonav.companion;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * A bounded, immutable fictional road graph and one route through it.
 * Coordinates are local integer metres, never GPS, latitude/longitude or real maps.
 */
public final class LocalNetwork {
    public static final int LOCAL_METERS_V1 = 1;
    public static final int MAX_NODES = 16;
    public static final int MAX_EDGES = 24;
    public static final int MAX_ROUTE_NODES = 16;
    public static final int MAX_COORDINATE_METERS = 2000;
    public static final int MAX_EDGE_METERS = 2000;
    public static final int MAX_LENGTH_MM = 10_000_000;

    public record Node(int x, int y) {
        public Node {
            if (x < -MAX_COORDINATE_METERS || x > MAX_COORDINATE_METERS
                    || y < -MAX_COORDINATE_METERS || y > MAX_COORDINATE_METERS) {
                throw new IllegalArgumentException("Local coordinates must be within +/-2000 metres");
            }
        }
    }

    /** Endpoints index the network's node list. Edges are undirected. */
    public record Edge(int a, int b) {
        public Edge {
            if (a < 0 || b < 0 || a == b) {
                throw new IllegalArgumentException("An edge needs two distinct nonnegative node indices");
            }
        }
    }

    private final int mapId;
    private final int coordinateVersion;
    private final List<Node> nodes;
    private final List<Edge> edges;
    private final List<Integer> route;
    private final int lengthMm;

    public LocalNetwork(int mapId, int coordinateVersion, List<Node> nodes,
                        List<Edge> edges, List<Integer> route) {
        if (mapId <= 0) throw new IllegalArgumentException("Map ID must be positive");
        if (coordinateVersion != LOCAL_METERS_V1) {
            throw new IllegalArgumentException("Only LOCAL_METERS_V1 is supported");
        }
        checkCount(nodes, 2, MAX_NODES, "nodes");
        checkCount(edges, 1, MAX_EDGES, "edges");
        checkCount(route, 2, MAX_ROUTE_NODES, "route nodes");
        List<Node> nodeCopy = new ArrayList<>(nodes);
        List<Edge> edgeCopy = new ArrayList<>(edges);
        List<Integer> routeCopy = new ArrayList<>(route);
        Set<Node> coordinates = new HashSet<>();
        for (Node node : nodeCopy) {
            if (node == null || !coordinates.add(node)) {
                throw new IllegalArgumentException("Nodes must be non-null and have distinct coordinates");
            }
        }
        Set<Integer> connections = new HashSet<>();
        for (Edge edge : edgeCopy) {
            if (edge == null || edge.a() >= nodeCopy.size() || edge.b() >= nodeCopy.size()) {
                throw new IllegalArgumentException("Edge endpoint is outside the node list");
            }
            Node a = nodeCopy.get(edge.a()), b = nodeCopy.get(edge.b());
            long dx = b.x() - a.x(), dy = b.y() - a.y();
            long squared = dx * dx + dy * dy;
            if (squared == 0 || squared > (long) MAX_EDGE_METERS * MAX_EDGE_METERS) {
                throw new IllegalArgumentException("Edge length must be in (0, 2000] metres");
            }
            if (!connections.add(edgeKey(edge.a(), edge.b()))) {
                throw new IllegalArgumentException("Duplicate undirected edge");
            }
        }
        long total = 0;
        for (int i = 0; i < routeCopy.size(); i++) {
            Integer nodeIndex = routeCopy.get(i);
            if (nodeIndex == null || nodeIndex < 0 || nodeIndex >= nodeCopy.size()) {
                throw new IllegalArgumentException("Route node is outside the node list");
            }
            if (i == 0) continue;
            int previous = routeCopy.get(i - 1);
            if (!connections.contains(edgeKey(previous, nodeIndex))) {
                throw new IllegalArgumentException("Each route segment must have a road edge");
            }
            Node a = nodeCopy.get(previous), b = nodeCopy.get(nodeIndex);
            total += Math.round(Math.hypot(b.x() - a.x(), b.y() - a.y()) * 1000.0);
            if (total > MAX_LENGTH_MM) {
                throw new IllegalArgumentException("Route exceeds 10000000 millimetres");
            }
        }
        this.mapId = mapId;
        this.coordinateVersion = coordinateVersion;
        this.nodes = Collections.unmodifiableList(nodeCopy);
        this.edges = Collections.unmodifiableList(edgeCopy);
        this.route = Collections.unmodifiableList(routeCopy);
        this.lengthMm = (int) total;
    }

    public LocalNetwork(int mapId, int coordinateVersion, int[][] nodes,
                        int[][] edges, int[] route) {
        this(mapId, coordinateVersion, toNodes(nodes), toEdges(edges), toRoute(route));
    }

    public int mapId() { return mapId; }
    public int coordinateVersion() { return coordinateVersion; }
    public List<Node> nodes() { return nodes; }
    public List<Edge> edges() { return edges; }
    public List<Integer> route() { return route; }
    public int lengthMm() { return lengthMm; }

    /** A fresh copy of the selected route only, for the simulator and renderer. */
    public double[][] points() {
        double[][] result = new double[route.size()][2];
        for (int i = 0; i < route.size(); i++) {
            Node node = nodes.get(route.get(i));
            result[i][0] = node.x();
            result[i][1] = node.y();
        }
        return result;
    }

    /** Two fictional routes to the same destination, sharing one small road graph. */
    public static LocalNetwork demo(int mapId, boolean detour) {
        int[][] nodes = {{0, 0}, {0, 240}, {180, 240}, {180, 480}, {-120, 480},
                {-120, 720}, {240, 720}, {240, 960}, {-120, 0}, {-120, 240}};
        int[][] edges = {{0, 1}, {1, 2}, {2, 3}, {3, 4}, {4, 5}, {5, 6}, {6, 7},
                {0, 8}, {8, 9}, {9, 1}};
        int[] route = detour ? new int[] {0, 8, 9, 1, 2, 3, 4, 5, 6, 7}
                : new int[] {0, 1, 2, 3, 4, 5, 6, 7};
        return new LocalNetwork(mapId, LOCAL_METERS_V1, nodes, edges, route);
    }

    private static int edgeKey(int a, int b) {
        return Math.min(a, b) * MAX_NODES + Math.max(a, b);
    }

    private static void checkCount(List<?> values, int min, int max, String name) {
        if (values == null || values.size() < min || values.size() > max) {
            throw new IllegalArgumentException("Invalid number of " + name);
        }
    }

    private static List<Node> toNodes(int[][] values) {
        if (values == null || values.length < 2 || values.length > MAX_NODES) {
            throw new IllegalArgumentException("Invalid number of nodes");
        }
        List<Node> result = new ArrayList<>(values.length);
        for (int[] value : values) {
            if (value == null || value.length != 2) throw new IllegalArgumentException("Node shape");
            result.add(new Node(value[0], value[1]));
        }
        return result;
    }

    private static List<Edge> toEdges(int[][] values) {
        if (values == null || values.length < 1 || values.length > MAX_EDGES) {
            throw new IllegalArgumentException("Invalid number of edges");
        }
        List<Edge> result = new ArrayList<>(values.length);
        for (int[] value : values) {
            if (value == null || value.length != 2) throw new IllegalArgumentException("Edge shape");
            result.add(new Edge(value[0], value[1]));
        }
        return result;
    }

    private static List<Integer> toRoute(int[] values) {
        if (values == null || values.length < 2 || values.length > MAX_ROUTE_NODES) {
            throw new IllegalArgumentException("Invalid number of route nodes");
        }
        List<Integer> result = new ArrayList<>(values.length);
        for (int value : values) result.add(value);
        return result;
    }
}

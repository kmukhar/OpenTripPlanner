package org.opentripplanner.routing.edgetype;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.LineString;
import org.locationtech.jts.geom.impl.PackedCoordinateSequence;
import org.opentripplanner.common.TurnRestriction;
import org.opentripplanner.routing.api.request.RouteRequest;
import org.opentripplanner.routing.core.BicycleOptimizeType;
import org.opentripplanner.routing.core.RoutingContext;
import org.opentripplanner.routing.core.State;
import org.opentripplanner.routing.core.StateData;
import org.opentripplanner.routing.core.TraverseMode;
import org.opentripplanner.routing.core.TraverseModeSet;
import org.opentripplanner.routing.graph.Graph;
import org.opentripplanner.routing.util.ElevationUtils;
import org.opentripplanner.routing.util.SlopeCosts;
import org.opentripplanner.routing.vertextype.IntersectionVertex;
import org.opentripplanner.routing.vertextype.StreetVertex;
import org.opentripplanner.transit.model.basic.NonLocalizedString;
import org.opentripplanner.util.geometry.GeometryUtils;

public class StreetEdgeTest {

  private static final double ONE_THIRD = 1 / 3.0;
  private static final double DELTA = 0.00001;
  private static final double SPEED = 6.0;

  private Graph graph;
  private IntersectionVertex v0, v1, v2;
  private RouteRequest proto;

  @BeforeEach
  public void before() {
    graph = new Graph();

    v0 = vertex("maple_0th", 0.0, 0.0); // label, X, Y
    v1 = vertex("maple_1st", 2.0, 2.0);
    v2 = vertex("maple_2nd", 1.0, 2.0);

    proto = new RouteRequest();
    proto.preferences().car().setSpeed(15.0f);
    proto.preferences().bike().setSpeed(5.0f);
    proto.preferences().bike().setWalkingSpeed(0.8);
    proto.preferences().bike().setReluctance(1.0);
    proto.preferences().car().setReluctance(1.0);
    proto
      .preferences()
      .withWalk(walk -> {
        walk.setSpeed(1.0);
        walk.setReluctance(1.0);
        walk.setStairsReluctance(1.0);
      });
    proto.preferences().street().setTurnReluctance(1.0);
    proto.setStreetSubRequestModes(TraverseModeSet.allModes());
  }

  @Test
  public void testInAndOutAngles() {
    // An edge heading straight West
    StreetEdge e1 = edge(v1, v2, 1.0, StreetTraversalPermission.ALL);

    // Edge has same first and last angle.
    assertEquals(90, e1.getInAngle());
    assertEquals(90, e1.getOutAngle());

    // 2 new ones
    StreetVertex u = vertex("test1", 2.0, 1.0);
    StreetVertex v = vertex("test2", 2.0, 2.0);

    // Second edge, heading straight North
    StreetEdge e2 = edge(u, v, 1.0, StreetTraversalPermission.ALL);

    // 180 degrees could be expressed as 180 or -180. Our implementation happens to use -180.
    assertEquals(180, Math.abs(e2.getInAngle()));
    assertEquals(180, Math.abs(e2.getOutAngle()));
  }

  @Test
  public void testTraverseAsPedestrian() {
    StreetEdge e1 = edge(v1, v2, 100.0, StreetTraversalPermission.ALL);
    e1.setCarSpeed(10.0f);

    RouteRequest options = proto.clone();
    options.setMode(TraverseMode.WALK);

    State s0 = new State(new RoutingContext(options, graph, v1, v2));
    State s1 = e1.traverse(s0);

    // Should use the speed on the edge.
    double expectedWeight = e1.getDistanceMeters() / options.preferences().walk().speed();
    long expectedDuration = (long) Math.ceil(expectedWeight);
    assertEquals(expectedDuration, s1.getElapsedTimeSeconds(), 0.0);
    assertEquals(expectedWeight, s1.getWeight(), 0.0);
  }

  @Test
  public void testTraverseAsCar() {
    StreetEdge e1 = edge(v1, v2, 100.0, StreetTraversalPermission.ALL);
    e1.setCarSpeed(10.0f);

    RouteRequest options = proto.clone();
    options.setMode(TraverseMode.CAR);

    State s0 = new State(new RoutingContext(options, graph, v1, v2));
    State s1 = e1.traverse(s0);

    // Should use the speed on the edge.
    double expectedWeight = e1.getDistanceMeters() / e1.getCarSpeed();
    long expectedDuration = (long) Math.ceil(expectedWeight);
    assertEquals(expectedDuration, s1.getElapsedTimeSeconds(), 0.0);
    assertEquals(expectedWeight, s1.getWeight(), 0.0);
  }

  @Test
  public void testModeSetCanTraverse() {
    StreetEdge e = edge(v1, v2, 1.0, StreetTraversalPermission.ALL);

    TraverseModeSet modes = TraverseModeSet.allModes();
    assertTrue(e.canTraverse(modes));

    modes = new TraverseModeSet(TraverseMode.BICYCLE, TraverseMode.WALK);
    assertTrue(e.canTraverse(modes));

    e = edge(v1, v2, 1.0, StreetTraversalPermission.CAR);
    assertFalse(e.canTraverse(modes));

    modes = new TraverseModeSet(TraverseMode.CAR, TraverseMode.WALK);
    assertTrue(e.canTraverse(modes));
  }

  /**
   * Test the traversal of two edges with different traverse modes, with a focus on cycling. This
   * test will fail unless the following three conditions are met: 1. Turn costs are computed based
   * on the back edge's traverse mode during reverse traversal. 2. Turn costs are computed such that
   * bike walking is taken into account correctly. 3. User-specified bike speeds are applied
   * correctly during turn cost computation.
   */
  @Test
  public void testTraverseModeSwitchBike() {
    StreetEdge e0 = edge(v0, v1, 50.0, StreetTraversalPermission.PEDESTRIAN);
    StreetEdge e1 = edge(v1, v2, 18.4, StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE);

    v1.trafficLight = true;

    RouteRequest forward = proto.clone();
    forward.preferences().bike().setSpeed(3.0f);
    forward.setMode(TraverseMode.BICYCLE);

    State s0 = new State(new RoutingContext(forward, graph, v0, v2));
    State s1 = e0.traverse(s0);
    State s2 = e1.traverse(s1);

    RouteRequest reverse = proto.clone();
    reverse.setArriveBy(true);
    reverse.preferences().bike().setSpeed(3.0f);
    reverse.setMode(TraverseMode.BICYCLE);

    State s3 = new State(new RoutingContext(reverse, graph, v0, v2));
    State s4 = e1.traverse(s3);
    State s5 = e0.traverse(s4);

    assertEquals(104, s2.getElapsedTimeSeconds());
    assertEquals(104, s5.getElapsedTimeSeconds());
  }

  /**
   * Test the traversal of two edges with different traverse modes, with a focus on walking. This
   * test will fail unless the following three conditions are met: 1. Turn costs are computed based
   * on the back edge's traverse mode during reverse traversal. 2. Turn costs are computed such that
   * bike walking is taken into account correctly. 3. Enabling bike mode on a routing request bases
   * the bike walking speed on the walking speed.
   */
  @Test
  public void testTraverseModeSwitchWalk() {
    StreetEdge e0 = edge(v0, v1, 50.0, StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE);
    StreetEdge e1 = edge(v1, v2, 18.4, StreetTraversalPermission.PEDESTRIAN);

    v1.trafficLight = true;

    RouteRequest forward = proto.clone();
    forward.setMode(TraverseMode.BICYCLE);

    State s0 = new State(new RoutingContext(forward, graph, v0, v2));
    State s1 = e0.traverse(s0);
    State s2 = e1.traverse(s1);

    RouteRequest reverse = proto.clone();
    reverse.setMode(TraverseMode.BICYCLE);
    reverse.setArriveBy(true);

    State s3 = new State(new RoutingContext(reverse, graph, v0, v2));
    State s4 = e1.traverse(s3);
    State s5 = e0.traverse(s4);

    assertEquals(42, s2.getElapsedTimeSeconds());
    assertEquals(42, s5.getElapsedTimeSeconds());
  }

  /**
   * Test the bike switching penalty feature, both its cost penalty and its separate time penalty.
   */
  @Test
  public void testBikeSwitch() {
    StreetEdge e0 = edge(v0, v1, 0.0, StreetTraversalPermission.PEDESTRIAN);
    StreetEdge e1 = edge(v1, v2, 0.0, StreetTraversalPermission.BICYCLE);
    StreetEdge e2 = edge(v2, v0, 0.0, StreetTraversalPermission.PEDESTRIAN_AND_BICYCLE);

    RouteRequest noPenalty = proto.clone();
    noPenalty.preferences().bike().setSwitchTime(0);
    noPenalty.preferences().bike().setSwitchCost(0);
    noPenalty.setMode(TraverseMode.BICYCLE);

    State s0 = new State(new RoutingContext(noPenalty, graph, v0, v0));
    State s1 = e0.traverse(s0);
    State s2 = e1.traverse(s1);
    State s3 = e2.traverse(s2);

    RouteRequest withPenalty = proto.clone();
    withPenalty.preferences().bike().setSwitchTime(42);
    withPenalty.preferences().bike().setSwitchCost(23);
    withPenalty.setMode(TraverseMode.BICYCLE);

    State s4 = new State(new RoutingContext(withPenalty, graph, v0, v0));
    State s5 = e0.traverse(s4);
    State s6 = e1.traverse(s5);
    State s7 = e2.traverse(s6);

    assertNull(s0.getBackMode());
    assertEquals(TraverseMode.WALK, s1.getBackMode());
    assertEquals(TraverseMode.BICYCLE, s2.getBackMode());
    assertEquals(TraverseMode.BICYCLE, s3.getBackMode());

    assertNull(s4.getBackMode());
    assertEquals(TraverseMode.WALK, s5.getBackMode());
    assertEquals(TraverseMode.BICYCLE, s6.getBackMode());
    assertEquals(TraverseMode.BICYCLE, s7.getBackMode());

    assertEquals(0, s0.getElapsedTimeSeconds());
    assertEquals(0, s1.getElapsedTimeSeconds());
    assertEquals(0, s2.getElapsedTimeSeconds());
    assertEquals(0, s3.getElapsedTimeSeconds());

    assertEquals(0.0, s0.getWeight(), 0.0);
    assertEquals(0.0, s1.getWeight(), 0.0);
    assertEquals(0.0, s2.getWeight(), 0.0);
    assertEquals(0.0, s3.getWeight(), 0.0);

    assertEquals(0.0, s4.getWeight(), 0.0);
    assertEquals(0.0, s5.getWeight(), 0.0);
    assertEquals(23.0, s6.getWeight(), 0.0);
    assertEquals(23.0, s7.getWeight(), 0.0);

    assertEquals(0, s4.getElapsedTimeSeconds());
    assertEquals(0, s5.getElapsedTimeSeconds());
    assertEquals(42, s6.getElapsedTimeSeconds());
    assertEquals(42, s7.getElapsedTimeSeconds());
  }

  @Test
  public void testTurnRestriction() {
    StreetEdge e0 = edge(v0, v1, 50.0, StreetTraversalPermission.ALL);
    StreetEdge e1 = edge(v1, v2, 18.4, StreetTraversalPermission.ALL);
    RouteRequest routingRequest = proto.clone();
    RoutingContext routingContext = new RoutingContext(routingRequest, graph, v0, v2);
    State state = new State(
      v2,
      Instant.EPOCH,
      routingContext,
      StateData.getInitialStateData(routingRequest)
    );

    state.getOptions().setArriveBy(true);
    e1.addTurnRestriction(new TurnRestriction(e1, e0, null, TraverseModeSet.allModes(), null));

    assertNotNull(e0.traverse(e1.traverse(state)));
  }

  @Test
  public void testElevationProfile() {
    var elevationProfile = new PackedCoordinateSequence.Double(
      new double[] { 0, 10, 50, 12 },
      2,
      0
    );
    StreetEdge e0 = edge(v0, v1, 50.0, StreetTraversalPermission.ALL, elevationProfile);

    assertArrayEquals(
      elevationProfile.toCoordinateArray(),
      e0.getElevationProfile().toCoordinateArray()
    );
  }

  /****
   * Private Methods
   ****/

  private IntersectionVertex vertex(String label, double x, double y) {
    return new IntersectionVertex(graph, label, x, y);
  }

  /**
   * Create an edge. If twoWay, create two edges (back and forth).
   */
  private StreetEdge edge(
    StreetVertex vA,
    StreetVertex vB,
    double length,
    StreetTraversalPermission perm
  ) {
    String labelA = vA.getLabel();
    String labelB = vB.getLabel();
    String name = String.format("%s_%s", labelA, labelB);
    Coordinate[] coords = new Coordinate[2];
    coords[0] = vA.getCoordinate();
    coords[1] = vB.getCoordinate();
    LineString geom = GeometryUtils.getGeometryFactory().createLineString(coords);

    return new StreetEdge(vA, vB, geom, name, length, perm, false);
  }

  private StreetEdge edge(
    StreetVertex vA,
    StreetVertex vB,
    double length,
    StreetTraversalPermission perm,
    PackedCoordinateSequence elevationProfile
  ) {
    String labelA = vA.getLabel();
    String labelB = vB.getLabel();
    String name = String.format("%s_%s", labelA, labelB);
    Coordinate[] coords = new Coordinate[2];
    coords[0] = vA.getCoordinate();
    coords[1] = vB.getCoordinate();
    LineString geom = GeometryUtils.getGeometryFactory().createLineString(coords);

    var edge = new StreetEdge(vA, vB, geom, name, length, perm, false);
    StreetElevationExtension.addToEdge(edge, elevationProfile, false);
    return edge;
  }

  @Test
  public void testBikeOptimizeTriangle() {
    // This test does not depend on the setup method - and can probably be simplified

    Coordinate c1 = new Coordinate(-122.575033, 45.456773);
    Coordinate c2 = new Coordinate(-122.576668, 45.451426);

    StreetVertex v1 = new IntersectionVertex(null, "v1", c1.x, c1.y, (NonLocalizedString) null);
    StreetVertex v2 = new IntersectionVertex(null, "v2", c2.x, c2.y, (NonLocalizedString) null);

    GeometryFactory factory = new GeometryFactory();
    LineString geometry = factory.createLineString(new Coordinate[] { c1, c2 });

    double length = 650.0;

    StreetEdge testStreet = new StreetEdge(
      v1,
      v2,
      geometry,
      "Test Lane",
      length,
      StreetTraversalPermission.ALL,
      false
    );
    testStreet.setBicycleSafetyFactor(0.74f); // a safe street

    Coordinate[] profile = new Coordinate[] {
      new Coordinate(0, 0), // slope = 0.1
      new Coordinate(length / 2, length / 20.0),
      new Coordinate(length, 0), // slope = -0.1
    };
    PackedCoordinateSequence elev = new PackedCoordinateSequence.Double(profile);
    StreetElevationExtension.addToEdge(testStreet, elev, false);

    SlopeCosts costs = ElevationUtils.getSlopeCosts(elev, true);
    double trueLength = costs.lengthMultiplier * length;
    double slopeWorkLength = testStreet.getEffectiveBikeDistanceForWorkCost();
    double slopeSpeedLength = testStreet.getEffectiveBikeDistance();

    var request = new RouteRequest(TraverseMode.BICYCLE);

    var bikePreferences = request.preferences().bike();
    bikePreferences.setOptimizeType(BicycleOptimizeType.TRIANGLE);
    bikePreferences.setSpeed(SPEED);
    request.preferences().setNonTransitReluctance(1);

    bikePreferences.initOptimizeTriangle(1, 0, 0);
    State startState = new State(v1, request, null);
    State result = testStreet.traverse(startState);
    double timeWeight = result.getWeight();
    double expectedTimeWeight = slopeSpeedLength / SPEED;
    assertEquals(expectedTimeWeight, result.getWeight(), DELTA);

    bikePreferences.initOptimizeTriangle(0, 1, 0);
    startState = new State(v1, request, null);
    result = testStreet.traverse(startState);
    double slopeWeight = result.getWeight();
    double expectedSlopeWeight = slopeWorkLength / SPEED;
    assertEquals(expectedSlopeWeight, slopeWeight, DELTA);
    assertTrue(length * 1.5 / SPEED < slopeWeight);
    assertTrue(length * 1.5 * 10 / SPEED > slopeWeight);

    bikePreferences.initOptimizeTriangle(0, 0, 1);
    startState = new State(v1, request, null);
    result = testStreet.traverse(startState);
    double slopeSafety = costs.slopeSafetyCost;
    double safetyWeight = result.getWeight();
    double expectedSafetyWeight = (trueLength * 0.74 + slopeSafety) / SPEED;
    assertEquals(expectedSafetyWeight, safetyWeight, DELTA);

    bikePreferences.initOptimizeTriangle(ONE_THIRD, ONE_THIRD, ONE_THIRD);
    startState = new State(v1, request, null);
    result = testStreet.traverse(startState);
    double expectedWeight = timeWeight * 0.33 + slopeWeight * 0.33 + safetyWeight * 0.34;
    assertEquals(expectedWeight, result.getWeight(), DELTA);
  }
}

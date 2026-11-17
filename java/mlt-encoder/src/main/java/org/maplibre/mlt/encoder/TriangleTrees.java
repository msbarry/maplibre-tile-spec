package org.maplibre.mlt.encoder;

import java.util.Arrays;

/**
 * The triangles of a layer coded as MLT v2 triangle trees: one 2-bit symbol per triangle, and the
 * outline of each tree as a cycle of vertex indices (v2 spec, "Triangle Trees").
 *
 * <p>Earcut adds no vertex, so every triangle it emits shares an edge with the ones before it. The
 * triangles of a feature are joined into a spanning forest across those shared edges. Each tree is
 * then stored as its outline plus, per triangle in preorder, whether a child lies across each of
 * its two other sides, which is enough to rebuild every triangle.
 */
final class TriangleTrees {

  private static final int NO_TWIN = -1;

  final LongList symbols = new LongList();
  final LongList cycles = new LongList();

  // Per feature, reused: the feature's triangle indices, and for each side (3 per triangle, side k
  // of a triangle running from its vertex k to the next) the side of the tree neighbour across it.
  private int[] indices = new int[0];
  private int[] twins = new int[0];
  private boolean[] claimed = new boolean[0];
  private final IntList roots = new IntList();
  private final IntList stack = new IntList();
  private final IntList outline = new IntList();
  // The sides ordered by the vertex they start at, for finding the side that runs a given way.
  private int[] sidesByFrom = new int[0];
  private int[] fromStarts = new int[0];
  private int minVertex;

  void fill(Triangles triangles) {
    symbols.clear();
    cycles.clear();
    int[] all = triangles.indices.array();
    int start = 0;
    for (int f = 0; f < triangles.countPerPolygonFeature.size(); f++) {
      int end = start + 3 * (int) triangles.countPerPolygonFeature.get(f);
      addFeature(Arrays.copyOfRange(all, start, end));
      start = end;
    }
  }

  private void addFeature(int[] featureIndices) {
    indices = featureIndices;
    growForest();
    for (int r = 0; r < roots.size(); r++) {
      outline(3 * roots.get(r));
      // Rerooting a tree only rotates its outline, so each tree starts at its smallest vertex,
      // which keeps the deltas between consecutive cycles small (a SHOULD of the spec).
      int first = 0;
      for (int i = 1; i < outline.size(); i++) {
        if (startsEarlier(outline.get(i), outline.get(first))) {
          first = i;
        }
      }
      addSymbols(outline.get(first));
      for (int i = 0; i < outline.size(); i++) {
        cycles.add(indices[outline.get((first + i) % outline.size())]);
      }
    }
  }

  /** Whether side {@code a} starts at a smaller vertex than {@code b}, or runs on to the next. */
  // An outline passes through a vertex more than once where the tree touches itself there, so
  // several sides can start at the smallest vertex. Preferring the one to the vertex after it
  // breaks the tie the way the Rust reference encoder does, so both write the same cycles.
  private boolean startsEarlier(int a, int b) {
    int fromA = indices[a];
    int fromB = indices[b];
    if (fromA != fromB) {
      return fromA < fromB;
    }
    return indices[next(a)] == fromA + 1 && indices[next(b)] != fromB + 1;
  }

  /**
   * Claims a tree from each unclaimed triangle in turn: a side joins the first triangle holding the
   * same edge reversed, unless that one is claimed already.
   */
  private void growForest() {
    int sides = indices.length;
    int triangleCount = sides / 3;
    if (twins.length < sides) {
      twins = new int[sides];
      claimed = new boolean[triangleCount];
    }
    Arrays.fill(twins, 0, sides, NO_TWIN);
    Arrays.fill(claimed, 0, triangleCount, false);
    indexSidesByFrom();
    roots.clear();
    for (int root = 0; root < triangleCount; root++) {
      if (claimed[root]) {
        continue;
      }
      claimed[root] = true;
      roots.add(root);
      stack.add(3 * root);
      while (stack.size() > 0) {
        int entry = stack.last();
        stack.truncate(stack.size() - 1);
        joinAcross(next(entry));
        joinAcross(next(next(entry)));
      }
    }
  }

  /** Joins the unclaimed triangle across {@code side}, if any, to the tree. */
  private void joinAcross(int side) {
    int child = sideFromTo(indices[next(side)], indices[side]);
    if (child != NO_TWIN && !claimed[child / 3]) {
      claimed[child / 3] = true;
      twins[side] = child;
      twins[child] = side;
      stack.add(child);
    }
  }

  /** Appends the symbol of each triangle of the tree entered across {@code entry}, in preorder. */
  private void addSymbols(int entry) {
    stack.add(entry);
    while (stack.size() > 0) {
      int side = stack.last();
      stack.truncate(stack.size() - 1);
      int left = next(side);
      int right = next(left);
      symbols.add((twins[left] != NO_TWIN ? 1 : 0) | (twins[right] != NO_TWIN ? 2 : 0));
      // Pushed right first, so the left subtree comes out first.
      if (twins[right] != NO_TWIN) {
        stack.add(twins[right]);
      }
      if (twins[left] != NO_TWIN) {
        stack.add(twins[left]);
      }
    }
  }

  /** Sets {@link #outline} to the sides of the tree that join nothing, in winding order. */
  private void outline(int entry) {
    outline.clear();
    outline.add(entry);
    stack.add(next(next(entry)));
    stack.add(next(entry));
    while (stack.size() > 0) {
      int side = stack.last();
      stack.truncate(stack.size() - 1);
      int child = twins[side];
      if (child == NO_TWIN) {
        outline.add(side);
      } else {
        stack.add(next(next(child)));
        stack.add(next(child));
      }
    }
  }

  /** Counting-sorts the sides by the vertex they start at. */
  private void indexSidesByFrom() {
    minVertex = Integer.MAX_VALUE;
    int maxVertex = Integer.MIN_VALUE;
    for (int vertex : indices) {
      minVertex = Math.min(minVertex, vertex);
      maxVertex = Math.max(maxVertex, vertex);
    }
    int vertexRange = indices.length == 0 ? 0 : maxVertex - minVertex + 1;
    if (fromStarts.length < vertexRange + 1) {
      fromStarts = new int[vertexRange + 1];
    }
    Arrays.fill(fromStarts, 0, vertexRange + 1, 0);
    for (int vertex : indices) {
      fromStarts[vertex - minVertex + 1]++;
    }
    for (int i = 1; i <= vertexRange; i++) {
      fromStarts[i] += fromStarts[i - 1];
    }
    if (sidesByFrom.length < indices.length) {
      sidesByFrom = new int[indices.length];
    }
    int[] fill = Arrays.copyOf(fromStarts, vertexRange);
    for (int side = 0; side < indices.length; side++) {
      sidesByFrom[fill[indices[side] - minVertex]++] = side;
    }
  }

  /** The first side running from {@code from} to {@code to}, or {@link #NO_TWIN}. */
  private int sideFromTo(int from, int to) {
    int bucket = from - minVertex;
    for (int i = fromStarts[bucket]; i < fromStarts[bucket + 1]; i++) {
      int side = sidesByFrom[i];
      if (indices[next(side)] == to) {
        return side;
      }
    }
    return NO_TWIN;
  }

  /** The side after {@code side} in its triangle. */
  private static int next(int side) {
    return side % 3 == 2 ? side - 2 : side + 1;
  }
}

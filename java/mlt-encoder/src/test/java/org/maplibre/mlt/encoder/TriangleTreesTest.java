package org.maplibre.mlt.encoder;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/** The same cases as the Rust reference encoder's triangle tree tests. */
class TriangleTreesTest {

  @Test
  void specExample() {
    var trees =
        encode(
            new int[] {1, 7},
            new int[][] {
              {1, 0, 2},
              {3, 6, 8},
              {7, 6, 3},
              {4, 3, 8},
              {7, 3, 5},
              {5, 4, 8},
              {8, 7, 5},
              {10, 9, 11}
            });
    assertEquals(List.of(0L, 2L, 1L, 1L, 2L, 1L, 0L, 0L), list(trees.symbols));
    assertEquals(
        List.of(0L, 2L, 1L, 3L, 6L, 8L, 7L, 6L, 3L, 5L, 4L, 9L, 11L, 10L), list(trees.cycles));
  }

  @Test
  void fanWithAHoleBridge() {
    var trees =
        encode(
            new int[] {4, 1}, new int[][] {{0, 1, 2}, {0, 2, 3}, {0, 3, 4}, {4, 3, 5}, {6, 7, 8}});
    assertEquals(List.of(2L, 2L, 1L, 0L, 0L), list(trees.symbols));
    assertEquals(List.of(0L, 1L, 2L, 3L, 5L, 4L, 6L, 7L, 8L), list(trees.cycles));
  }

  private static TriangleTrees encode(int[] trianglesPerFeature, int[][] triangles) {
    var input = new Triangles();
    for (int count : trianglesPerFeature) {
      input.countPerPolygonFeature.add(count);
    }
    for (int[] triangle : triangles) {
      for (int vertex : triangle) {
        input.indices.add(vertex);
      }
    }
    var trees = new TriangleTrees();
    trees.fill(input);
    return trees;
  }

  private static List<Long> list(LongList values) {
    return java.util.stream.IntStream.range(0, values.size()).mapToObj(values::get).toList();
  }
}

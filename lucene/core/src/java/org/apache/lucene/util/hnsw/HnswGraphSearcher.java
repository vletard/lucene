/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.lucene.util.hnsw;

import static org.apache.lucene.search.DocIdSetIterator.NO_MORE_DOCS;

import java.io.IOException;
import org.apache.lucene.index.KnnGraphValues;
import org.apache.lucene.index.RandomAccessVectorValues;
import org.apache.lucene.index.VectorSimilarityFunction;
import org.apache.lucene.util.BitSet;
import org.apache.lucene.util.Bits;
import org.apache.lucene.util.SparseFixedBitSet;

/**
 * Searches an HNSW graph to find nearest neighbors to a query vector. For more background on the
 * search algorithm, see {@link HnswGraph}.
 */
public final class HnswGraphSearcher {
  private final VectorSimilarityFunction similarityFunction;
  /**
   * Scratch data structures that are used in each {@link #searchLevel} call. These can be expensive
   * to allocate, so they're cleared and reused across calls.
   */
  private final NeighborQueue candidates;

  private final BitSet visited;

  /**
   * Creates a new graph searcher.
   *
   * @param similarityFunction the similarity function to compare vectors
   * @param candidates max heap that will track the candidate nodes to explore
   * @param visited bit set that will track nodes that have already been visited
   */
  HnswGraphSearcher(
      VectorSimilarityFunction similarityFunction, NeighborQueue candidates, BitSet visited) {
    this.similarityFunction = similarityFunction;
    this.candidates = candidates;
    this.visited = visited;
  }

  /**
   * Searches HNSW graph for the nearest neighbors of a query vector.
   *
   * @param query search query vector
   * @param topK the number of nodes to be returned
   * @param vectors the vector values
   * @param similarityFunction the similarity function to compare vectors
   * @param graphValues the graph values. May represent the entire graph, or a level in a
   *     hierarchical graph.
   * @param acceptOrds {@link Bits} that represents the allowed document ordinals to match, or
   *     {@code null} if they are all allowed to match.
   * @return a priority queue holding the closest neighbors found
   */
  public static NeighborQueue search(
      float[] query,
      int topK,
      RandomAccessVectorValues vectors,
      VectorSimilarityFunction similarityFunction,
      KnnGraphValues graphValues,
      Bits acceptOrds)
      throws IOException {
    HnswGraphSearcher graphSearcher =
        new HnswGraphSearcher(
            similarityFunction,
            new NeighborQueue(topK, similarityFunction.reversed == false),
            new SparseFixedBitSet(vectors.size()));
    NeighborQueue results;
    int[] eps = new int[] {graphValues.entryNode()};
    for (int level = graphValues.numLevels() - 1; level >= 1; level--) {
      results = graphSearcher.searchLevel(query, 1, level, eps, vectors, graphValues, null);
      eps[0] = results.pop();
    }
    results = graphSearcher.searchLevel(query, topK, 0, eps, vectors, graphValues, acceptOrds);
    return results;
  }

  /**
   * Searches for the nearest neighbors of a query vector in a given level
   *
   * @param query search query vector
   * @param topK the number of nearest to query results to return
   * @param level level to search
   * @param eps the entry points for search at this level expressed as level 0th ordinals
   * @param vectors vector values
   * @param graphValues the graph values
   * @param acceptOrds {@link Bits} that represents the allowed document ordinals to match, or
   *     {@code null} if they are all allowed to match.
   * @return a priority queue holding the closest neighbors found
   */
  NeighborQueue searchLevel(
      float[] query,
      int topK,
      int level,
      final int[] eps,
      RandomAccessVectorValues vectors,
      KnnGraphValues graphValues,
      Bits acceptOrds)
      throws IOException {
    int size = graphValues.size();
    NeighborQueue results = new NeighborQueue(topK, similarityFunction.reversed);
    clearScratchState();

    for (int ep : eps) {
      if (visited.getAndSet(ep) == false) {
        float score = similarityFunction.compare(query, vectors.vectorValue(ep));
        candidates.add(ep, score);
        if (acceptOrds == null || acceptOrds.get(ep)) {
          results.add(ep, score);
        }
      }
    }

    // A bound that holds the minimum similarity to the query vector that a candidate vector must
    // have to be considered.
    BoundsChecker bound = BoundsChecker.create(similarityFunction.reversed);
    if (results.size() >= topK) {
      bound.set(results.topScore());
    }
    while (candidates.size() > 0) {
      // get the best candidate (closest or best scoring)
      float topCandidateScore = candidates.topScore();
      if (bound.check(topCandidateScore)) {
        break;
      }
      int topCandidateNode = candidates.pop();
      graphValues.seek(level, topCandidateNode);
      int friendOrd;
      while ((friendOrd = graphValues.nextNeighbor()) != NO_MORE_DOCS) {
        assert friendOrd < size : "friendOrd=" + friendOrd + "; size=" + size;
        if (visited.getAndSet(friendOrd)) {
          continue;
        }

        float score = similarityFunction.compare(query, vectors.vectorValue(friendOrd));
        if (bound.check(score) == false) {
          candidates.add(friendOrd, score);
          if (acceptOrds == null || acceptOrds.get(friendOrd)) {
            if (results.insertWithOverflow(friendOrd, score) && results.size() >= topK) {
              bound.set(results.topScore());
            }
          }
        }
      }
    }
    while (results.size() > topK) {
      results.pop();
    }
    results.setVisitedCount(visited.approximateCardinality());
    return results;
  }

  private void clearScratchState() {
    candidates.clear();
    visited.clear(0, visited.length());
  }
}

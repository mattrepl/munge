(ns munge.graph
  (:require [schema.core :as s]
            [clojure.core.typed :as t]
            [clojure.core.reducers :as r]
            [clojure.set :refer [intersection]]
            [loom.graph :as lg]
            [loom.alg :as la]
            [loom.attr :as lat]
            [munge.core :refer [update-for-keys]]
            [munge.schema :refer [Matrix Graph WeightedGraph]]
            [clojure.core.matrix :refer [zero-matrix sparse-matrix mset!] :as mx])
  (:import [java.util BitSet]))

;; TODO: Fix the need for selected-nodes?
(s/defn selected-path-distances :- {s/Any {s/Any s/Int}}
  "The max depth is inclusive."
  [g :- Graph
   selected-nodes :- #{s/Any}
   selector :- (s/=> s/Bool s/Any s/Int)
   result :- (s/=> s/Any s/Any s/Int)
   max-depth :- s/Int]
  (letfn [(f [node pred-map depth]
            (if (selector node depth)
              (result node depth)
              nil))
          (whn [neighbor pred depth]
            (<= depth max-depth))]
    (->> (reduce (s/fn [paths :- {s/Any {s/Any s/Int}}
                         n :- s/Any]
                   (->> (la/bf-traverse g n :f f :when whn)
                        (into {})
                        (assoc paths n)))
                 {}
                 selected-nodes)
         (into {}))))

(s/defn membership-graph :- WeightedGraph
  "Create an membership graph given an undirected graph
  and a map of communities to members. A community graph is
  a complete graph with weighted edges where communities are nodes,  
  and edge weights indicate network distance between communities.
  The max depth is inclusive.

  The network distance is calculated by adding edges from communities
  to all nodes adjacent to each community's members and then using the
  distance over the original and community nodes. NB: using the community
  nodes is easier since they become a part of the network but won't affect
  the shortest paths between communities since they will always add a hop.

  Since community nodes do not have edges with each other, note that the max-depth
  should be adjusted to account for the extra steps in the path when finding new nodes
  and for the reported distance in the result. Community distance is based on the number
  of original nodes used in the path but subtracted by one. That is, if a single node 
  belongs to two communities, then a network path of distance 2 connects the two communities.
  However, that node is a member of both communities and thus the communities overlap.
  Overlapping communities have a distance of 0.

  Example:
  (C1) <-> (A) <-> (C2), distance: 2, comm distance: 0
  (C1) <-> (A) <-> (B) <-> (C3), distance: 3, comm distance: 1
  (C1) <-> (A) <-> (C2) <-> (B) <-> (C3), distance: 4, comm distance: ?

  The last example will not be found as a shortest path since the path before it,
  without (C2), is shorter. Paths that include communities other than start- and
  end-points.

  Note that community names must not conflict with original node names."
  [g :- Graph
   comms :- {(s/either s/Str s/Keyword) #{s/Any}}
   max-depth :- s/Int]

  ;; Add comm nodes to graph
  ;; Find shortest paths
  ;; Return graph with only edges (orig-node X comm)
  (let [comm-keys (set (keys comms))
        path-results (-> g
                         (lg/add-edges* (for [c-key comm-keys c-memb (get comms c-key)]
                                       [c-key c-memb 1]))
                         (selected-path-distances comm-keys
                                                  (fn [node depth]
                                                    (and (> depth 0)
                                                         (contains? comm-keys node)))
                                                  (fn [node depth]
                                                    [node (- depth 2)])
                                                  (+ 2 max-depth))) ; Extra depth to account for edges between end-point communities.
        comm-es (for [u (keys path-results)
                 [v d] (get path-results u)]
                  [u v d])]
    (-> (lg/weighted-graph)
        (lg/add-nodes* comm-keys)
        (lg/add-edges* comm-es))))

(s/defn coincident-edges :- {s/Any {s/Any s/Int}}
  "Create a edges from a collection of sets of 
  vertices. Vertices are linked if they are both members of at least one
  common set. Edge weight indicates the number of common sets between
  the vertex pair."
  [ss :- [java.util.Set]]  
  (let [rindex (reduce (fn [acc-idx s]
                         (update-for-keys acc-idx #(conj % s) (seq s) []))
                       {}
                       ss)
        edges (reduce (fn [acc-edges [u ss-u]]
                        (assoc acc-edges u (->> (apply concat ss-u)
                                                (filter (partial not= u))
                                                (group-by identity)
                                                (map (juxt first (comp count second)))
                                                (into {}))))
                      {}
                      rindex)]
    edges))

(s/defn coincident-graph :- WeightedGraph
  "Create a graph from a collection of sets of 
  vertices. Vertices are linked if they are both members of at least one
  common set. Edge weight indicates the number of common sets between
  the vertex pair."
  [ss :- [java.util.Set]]
  (lg/weighted-graph (coincident-edges ss)))

(s/defn adj-matrix :- Matrix
  "Create an adjacency matrix for the graph."
  [g :- Graph
   node-index :- {s/Any s/Int}]
  (let [num-nodes (count (lg/nodes g))
        m (-> (zero-matrix num-nodes num-nodes) sparse-matrix mx/mutable)
        weight (if (lg/weighted? g) (lg/weight g) (constantly 1))]
    ;; Fetch edges by node
    (doseq [[u v] (lg/edges g)]
      (mset! m (get node-index u) (get node-index v) (weight u v)))
    (mx/immutable m)))

(def GroupNode {:name s/Str
                :size s/Int
                :members #{s/Any}})
(s/defn group-nodes :- [GroupNode]
  [groups :- [#{s/Any}]]
  (map-indexed (fn [idx group]
                 {:name (str idx)
                  :size (count group)
                  :members group})
               groups))

(def GroupEdge [(s/one s/Any "src") (s/one s/Any "dst") (s/one s/Int "weight")])
(s/defn group-edges :- [GroupEdge]
  "Construct undirected group edges."
  [nodes :- [GroupNode]]
  (for [src nodes
        dst nodes
        :let [weight (count (intersection (:members src)
                                          (:members dst)))]
        :when (and (not= (:name src) (:name dst))
                   (not (zero? weight)))]
    ;; Num shared researchers
    [src dst weight]))

(s/defn group-graph :- WeightedGraph
  "Create a graph from a collection of sets of vertices. There will be a node for each
  set and edges between sets which share set items in common. The edge weights are set
  to the number of common items."
  [ss :- [java.util.Set]]
  (let [nodes (group-nodes ss)]
    (-> (lg/weighted-graph)
        (lg/add-nodes* nodes)
        (lg/add-edges* (group-edges nodes)))))

(s/defn largest-connected-component :- Graph
  [g :- Graph]
  (lg/subgraph g
               (->> g
                    (la/connected-components)
                    (sort-by count >)
                    (first))))

(s/defn add-attr-fn-to-all :- Graph
  "Adds an attribute with key attr-key to every node
  with a value specified by get-attr-val."
  [g :- Graph
   attr-key :- s/Keyword
   get-attr-val :- (s/=> s/Any s/Any)]
  (loop [g* g
         ns (lg/nodes g)]
    (if (empty? ns)
      g*
      (let [n (first ns)]
        (recur (lat/add-attr g* n attr-key (get-attr-val n))
               (rest ns))))))

;; TODO: Adding a default weight seems silly. Can we avoid this
;;       by using better graph implementations and helpers?
;;       E.g., a function that wraps an unweighted (or weighted)
;;       graph and responds with "1" for all weights of valid edges.
(t/defn subgraph-edges
  "Grab edges connecting a subset of nodes."
  [g :- Graph
   ns :- (t/Seq MapNode)] :- (t/Seq WeightedEdge)
   (->> (lg/edges g)
        (filter (fn [[n1 n2]] (and (contains? ns n1)
                                   (contains? ns n2))))
        (map (comp #(conj % 1) vec))))

;; TODO: Passing in edges as a collection increases performance
;;       over passing in the graph and calling loom.graph/edges, when
;;       we want to use the same collection of edges multiple times.
;;       This is due to how the edges are represented by the default
;;       loom impl.
;; TODO: Extract relevant code and make an IdSet set type that uses a get-id fn
;;       and implements the Set protocol.
(t/defn subgraph-edges-by-id
  "Grab edges connecting a subset of nodes.
  
  WARNING: A BitSet is used to hold contiguous node IDs.
  The BitSet will be of size (apply max node-ids)."
  [es :- (t/Coll Edges)
   get-id :- (t/Fn [MapNode -> t/Int])
   ns :- (t/Seq MapNode)] :- (t/Seq WeightedEdge)
   (let [n-ids (->> ns (map get-id) distinct)
         max-id (r/fold (fn max*
                          ([] 0)
                          ([x] x)
                          ([x y] (max x y)))
                        n-ids)
         n-id-set (BitSet. max-id)]
     (doseq [n-id n-ids]
       (.set ^BitSet n-id-set n-id))
     (->> es
          (filter (fn [[n1 n2]] (and (.get ^BitSet n-id-set (get-id n1)) 
                                     (.get ^BitSet n-id-set (get-id n2))))))))

;; TODO: needs lots of work to generalize, better to use new impl of Graph/WeightedGraph (complete those).
(t/defn edges->graph
  "Create a graph from edges."
  [es :- (t/Seq WeightedEge)] :- Graph
   (-> (lg/weighted-graph)
       (lg/add-edges* es)))

(t/defalias SpanTree (t/Map MapNode (t/Vec MapNode)))

;; TODO: write test for this
(t/defn span-tree->paths
  "Create a sequence of paths from predecessor maps.
  E.g., those returned by loom.alg/bf-all-pairs-shortest-paths."
  [span-tree :- SpanTree
   src :- MapNode]
  (loop [paths [[src]]
         to-expand (set (map (partial vector src) (get span-tree src)))]
    (if (empty? to-expand)
      paths
      ;; These are the current paths which may need to be expanded if the last node
      ;; in the path is connected to something else that hasn't been reached by another
      ;; path.
      (let [next-path (first to-expand)
            node (last next-path)
            reached-nodes (get span-tree node)
            new-paths (map (partial conj next-path) reached-nodes)]
        (recur (concat paths new-paths)
               (apply conj (disj to-expand next-path) new-paths))))))

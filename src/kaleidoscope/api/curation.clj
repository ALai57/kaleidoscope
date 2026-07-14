(ns kaleidoscope.api.curation
  (:require [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Novelty split (explore/exploit) + relevance threshold
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def default-shelf-size 6)

(def ^:private relevance-configs
  {"quick"    {:scrutiny "quick"    :relevance-threshold 5.0}
   "standard" {:scrutiny "standard" :relevance-threshold 6.0}
   "rigorous" {:scrutiny "rigorous" :relevance-threshold 7.0}})

(defn relevance-config
  [level]
  (get relevance-configs (or level "standard") (get relevance-configs "standard")))

(defn novelty-quota
  "Split shelf-size slots into {:trusted n :novel m} from the explore dial.
  novelty-ratio 0.0 = all trusted, 1.0 = all novel. The novel share rounds to
  the nearest slot, so 0.5 on an odd shelf gives the extra slot to novel."
  [shelf-size novelty-ratio]
  (let [ratio (-> (double (or novelty-ratio 0.5)) (max 0.0) (min 1.0))
        novel (int (Math/round (* ratio shelf-size)))]
    {:novel novel :trusted (- shelf-size novel)}))

(defn tag-origin
  "Tag each candidate :origin trusted/novel by case-insensitive membership of
  its source in trusted-sources. Origin is decided here, in code — never by
  the LLM — so the \"new source\" tag can't be smuggled or forgotten."
  [candidates trusted-sources]
  (let [trusted? (into #{} (map str/lower-case) (or trusted-sources []))]
    (mapv (fn [{:keys [source] :as candidate}]
            (assoc candidate :origin
                   (if (trusted? (str/lower-case (or source ""))) "trusted" "novel")))
          candidates)))

(defn drop-below-threshold
  "Drop candidates whose relevance is below threshold (missing = 0.0)."
  [candidates threshold]
  (vec (filter #(>= (double (or (:relevance %) 0.0)) (double threshold)) candidates)))

(defn split-candidates
  "Fill the shelf from an origin-tagged pool: the trusted quota from trusted
  candidates (best relevance first), the novel quota from novel candidates,
  then backfill any shortfall from whatever remains so a thin pool still
  fills the shelf as far as it can."
  [candidates {:keys [trusted novel]}]
  (let [by-relevance  (fn [pool] (sort-by #(- (double (or (:relevance %) 0.0))) pool))
        pools         (group-by :origin candidates)
        trusted-picks (vec (take trusted (by-relevance (get pools "trusted"))))
        novel-picks   (vec (take novel (by-relevance (get pools "novel"))))
        picked?       (set (concat trusted-picks novel-picks))
        shortfall     (- (+ trusted novel)
                         (+ (count trusted-picks) (count novel-picks)))
        backfill      (take (max 0 shortfall)
                            (by-relevance (remove picked? candidates)))]
    (vec (concat trusted-picks novel-picks backfill))))

(defn parse-candidates
  "Parse a Discover step's output into candidate maps. The librarian JSON
  contract uses est_time; normalize to :est-time. Returns [] on any parse
  failure — a malformed discovery shelves nothing rather than throwing."
  [output]
  (try
    (->> (:candidates (json/decode output true))
         (mapv #(set/rename-keys % {:est_time :est-time})))
    (catch Exception _ [])))

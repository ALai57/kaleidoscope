(ns kaleidoscope.utils.path-matching
  "Pure string matching for project-to-codebase path resolution.
   No I/O, no database. Takes strings in, returns scores out."
  (:require [clojure.string :as str]
            [taoensso.timbre :as log])
  (:import [java.io File]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Constants
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(def ^:private AUTO-USE-THRESHOLD 0.80)
(def ^:private MIN-WORD-LENGTH 3)
(def ^:private MAX-CANDIDATES 50)

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Normalisation
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn normalize
  "Lowercase, replace [-_/. ] with spaces, trim."
  [s]
  (when s
    (-> s
        str/lower-case
        (str/replace #"[-_/\. ]+" " ")
        str/trim)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Candidate scoring
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn score-candidate
  "Compute a match score (0.0–1.0) between a candidate directory name and project metadata.
   Returns {:score 0.0-1.0 :reason \"...\"}."
  [candidate-basename project-title project-description]
  (let [n (normalize candidate-basename)
        t (normalize project-title)
        d (normalize project-description)]
    (cond
      ;; 1.0 — exact match
      (= n t)
      {:score 1.0 :reason "exact title match"}

      ;; 0.85 — title contains dir name
      (and (seq n) (seq t) (str/includes? t n))
      {:score 0.85 :reason "title contains directory name"}

      ;; 0.80 — dir name contains title
      (and (seq n) (seq t) (str/includes? n t))
      {:score 0.80 :reason "directory name contains title"}

      ;; 0.65 — word overlap (any word in title appears in dir name, min length 4)
      (and (seq n) (seq t)
           (some (fn [word]
                   (and (> (count word) MIN-WORD-LENGTH)
                        (str/includes? n word)))
                 (str/split t #"\s+")))
      {:score 0.65 :reason "word overlap between title and directory name"}

      ;; 0.60 — dir name mentioned in description
      (and (seq n) (seq d) (str/includes? d n))
      {:score 0.60 :reason "directory name mentioned in description"}

      :else
      {:score 0.0 :reason "no match"})))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Match finding
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn find-best-match
  "Rank candidates against the project. Returns:
     {:best {:path \"...\" :score 0.0-1.0 :reason \"...\"}  ; nil if no candidate clears auto-use threshold
      :ranked [{:path :score :reason} ...]}             ; all candidates, sorted by score desc"
  [candidates project]
  (let [title  (:title project)
        desc   (:description project)
        scored (->> candidates
                    (map (fn [{:keys [path basename]}]
                           (let [{:keys [score reason]} (score-candidate basename title desc)]
                             {:path path :basename basename :score score :reason reason})))
                    (sort-by :score >)
                    (take MAX-CANDIDATES)
                    vec)
        top    (first scored)]
    {:best   (when (and top (>= (:score top) AUTO-USE-THRESHOLD))
               ;; Only auto-use if exactly one candidate clears the threshold
               (let [above-threshold (filter #(>= (:score %) AUTO-USE-THRESHOLD) scored)]
                 (when (= 1 (count above-threshold)) top)))
     :ranked scored}))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Workspace root scanning
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn scan-workspace-roots
  "Return registered workspace roots as candidates for code context selection.
   Each root is offered directly — no subdirectory enumeration.
   Skips roots that don't exist or aren't directories.
   Returns [{:path \"...\" :basename \"...\"}]."
  [root-paths]
  (->> root-paths
       (keep (fn [root-path]
               (let [f (File. ^String root-path)]
                 (if (and (.exists f) (.isDirectory f))
                   {:path     (.getAbsolutePath f)
                    :basename (.getName f)}
                   (do (log/warnf "Workspace root does not exist or is not a directory: %s" root-path)
                       nil)))))
       vec))

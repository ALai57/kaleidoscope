(ns andrewslai.clj.files.core
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.files.mime-types :as mime]
            [clojure.string :as string]
            [ring.util.mime-type :as mt]
            [clojure.spec.alpha :as s]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Setting up a hierarchy for multimethods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def DEFAULT-PREFIX
  "The prefix for all file related classes (e.g. <prefix>/subtype)"
  "file")

(defn make-type
  [s]
  (symbol (format "%s/%s" DEFAULT-PREFIX s)))

(def file-hierarchy
  (let [hierarchy (make-hierarchy)]
    (reduce-kv (fn [hierarchy ext mime-type]
                 (derive hierarchy
                         (make-type ext)
                         (make-type (mime/get-type mime-type))))
               hierarchy
               mt/default-mime-types)))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Extensible multimethod for extracting metadata from different classes
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti extract-meta class)


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multimethod for putting files into a filesystem
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti put-file (fn infer-file-type
                     [filesystem path input metadata]
                     (make-type (#'mt/filename-ext path)))
  :hierarchy #'file-hierarchy)

(defmethod put-file 'file/image
  [filesystem path input metadata]
  (fs/put-file filesystem
               path
               input
               (merge (extract-meta input) metadata)))




(comment
  (get-type (second (first mt/default-mime-types)))



  )











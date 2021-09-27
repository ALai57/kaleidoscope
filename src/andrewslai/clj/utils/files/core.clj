(ns andrewslai.clj.utils.files.core
  (:require [andrewslai.clj.persistence.filesystem :as fs]
            [andrewslai.clj.utils.files.mime-types :as mime]
            [clojure.string :as string]
            [ring.util.mime-type :as mt]
            [clojure.spec.alpha :as s])
  (:import [org.apache.commons.io IOUtils]
           [java.io InputStream]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Setting up a hierarchy for multimethods
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(def PREFIX
  "The prefix for all file related classes (e.g. <prefix>/subtype)"
  "file")

(defn make-type
  [s]
  (symbol (format "%s/%s" PREFIX s)))

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
(defmulti extract-meta (fn infer-file-type
                         [path input-stream]
                         (make-type (#'mt/filename-ext path)))
  :hierarchy #'file-hierarchy)

(comment
  (make-type (#'mt/filename-ext "resources/public/images/earthrise.png"))
  )
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Multimethod for putting things into a filesystem
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
(defmulti put-file (fn [filesystem path input metadata]
                     (class input)))

(defmethod put-file java.io.File
  [filesystem path input metadata]
  (fs/put-file filesystem
               path
               input
               (merge (extract-meta path input) metadata)))


;; WHAT TO DO ABOUT SVG IMAGES, WHICH CANNOT BE READ WITH imageIO?
;; Should the `put-file` method dispatch on the file type too?



(comment
  (IOUtils/toByteArray ^InputStream (:stream item))

  (defn input->stream
    [])

  (defn put-file*
    [filesystem path input metadata]
    (let [input-stream (input->stream input)]
      (fs/put-file filesystem
                   path
                   input-stream
                   (merge (extract-meta path input-stream) metadata))))

  (get-type (second (first mt/default-mime-types)))

  )











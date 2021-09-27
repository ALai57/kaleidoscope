(ns andrewslai.clj.utils.files.mime-types
  (:require [clojure.spec.alpha :as s]
            [clojure.string :as string]))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;; Helpers for working with MIME-type strings `<type>/<subtype>`
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

(defn mime-type?
  "MIME types should be of form type/subtype"
  [s]
  (= 2 (count (string/split s #"/"))))

(s/def ::mime-type (s/and string? mime-type?))

(defn get-type
  [mime-type]
  (first (string/split mime-type #"/")))

(defn get-subtype
  [mime-type]
  (second (string/split mime-type #"/")))

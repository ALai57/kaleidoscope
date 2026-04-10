(ns kaleidoscope.utils.versioning
  (:require [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]))

(def version-path
  "version.properties")

(defn parse-properties
  [resource-path]
  (some->> (io/resource resource-path)
           slurp
           (#(string/split % #"\n|="))
           (partition 2)
           (map vec)
           (into {})
           walk/keywordize-keys))

(defn get-version-details []
  (or (parse-properties version-path)
      {:version "unknown" :revision "unknown"}))

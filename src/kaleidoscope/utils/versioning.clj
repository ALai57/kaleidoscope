(ns kaleidoscope.utils.versioning
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :refer [trim]]))

(def pom-path
  "META-INF/maven/org.clojars.alai57/kaleidoscope/pom.properties")

(defn parse-pom
  [pom-path]
  (some->> (clojure.java.io/resource pom-path)
           slurp
           (#(clojure.string/split % #"\n|="))
           (partition 2)
           (map vec)
           (into {})
           clojure.walk/keywordize-keys))

(defn short-sha
  []
  (-> (sh "git" "rev-parse" "--short" "HEAD")
      :out
      trim))

(defn get-version-details []
  (or (parse-pom pom-path)
      {:revision (short-sha)
       :version  "version-not-set"}))

(ns kaleidoscope.utils.versioning
  (:require [clojure.java.shell :as shell]
            [clojure.java.io :as io]
            [clojure.string :as string]
            [clojure.walk :as walk]))

(def pom-path
  "META-INF/maven/org.clojars.alai57/kaleidoscope/pom.properties")

(defn parse-pom
  [pom-path]
  (some->> (io/resource pom-path)
           slurp
           (#(string/split % #"\n|="))
           (partition 2)
           (map vec)
           (into {})
           walk/keywordize-keys))

(defn short-sha
  []
  (-> (shell/sh "git" "rev-parse" "--short" "HEAD")
      :out
      string/trim))

(defn get-version-details []
  (or (parse-pom pom-path)
      {:revision (short-sha)
       :version  "version-not-set"}))

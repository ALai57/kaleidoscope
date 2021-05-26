(ns andrewslai.clj.routes.ping
  (:require [clojure.java.shell :refer [sh]]
            [clojure.string :refer [trim]]
            [compojure.api.sweet :refer [defroutes GET]]
            [ring.util.http-response :refer [ok]]))

(def pom-path
  "META-INF/maven/org.clojars.andrewslai/andrewslai/pom.properties")

(defn parse-pom
  [pom-path]
  (some->> (clojure.java.io/resource pom-path)
           slurp
           (#(clojure.string/split % #"\n|="))
           (partition 2)
           (map vec)
           (into {})
           clojure.walk/keywordize-keys))

(defn get-version-details []
  (or (parse-pom pom-path)
      {:revision (-> (sh "git" "rev-parse" "--short" "HEAD")
                     :out
                     trim)}))

(defn ping-handler []
  (ok (get-version-details)))

(defroutes ping-routes
  (GET "/ping" []
    (ping-handler)))

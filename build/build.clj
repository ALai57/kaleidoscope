(ns kaleidoscope.build
  (:require [clojure.tools.build.api :as b]))

(def LIB 'org.clojars.alai57/kaleidoscope)
(def MAIN (symbol (format "%s.main" (name LIB))))
(def VERSION (format "0.2.%s" (b/git-count-revs nil)))
(def BASIS (b/create-basis {:project "deps.edn"}))

;; Directory structure
(def OUTPUT-DIR "target2")
(def CLASS-DIR (format "%s/classes" OUTPUT-DIR))
(def UBER-FILE (format "%s/%s.jar" OUTPUT-DIR (name LIB)))

(defn clean [_]
  (b/delete {:path OUTPUT-DIR}))

(defn uberjar [_]
  (clean nil)
  (b/write-pom {:class-dir CLASS-DIR
                :lib       LIB
                :version   VERSION
                :basis     BASIS
                :src-dirs  ["src" "resources"]
                })
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir CLASS-DIR})
  (b/compile-clj {:basis        BASIS
                  :src-dirs     ["src"]
                  :class-dir    CLASS-DIR})
  (b/uber {:class-dir CLASS-DIR
           :uber-file UBER-FILE
           :basis     BASIS
           :main      MAIN}))

(defn dockerize [_]
  )

(comment
  (uberjar nil)
  )

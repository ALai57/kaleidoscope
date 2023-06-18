(ns kaleidoscope.build
  (:refer-clojure :exclude [compile])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as shell]))

(def LIB 'org.clojars.alai57/kaleidoscope)
(def MAIN (symbol (format "%s.main" (name LIB))))
(def VERSION (format "0.2.%s" (b/git-count-revs nil)))
(def BASIS (b/create-basis {:project "deps.edn"}))

;; Directory structure
(def OUTPUT-DIR "target")
(def CLASS-DIR (format "%s/classes" OUTPUT-DIR))
(def UBER-FILE (format "%s/%s.jar" OUTPUT-DIR (name LIB)))

(defn git-status []
  (shell/sh "git" "status"))

(def nonzero?
  (complement zero?))

(defn assert-committed
  [_]
  (let [{:keys [exit out] :as result} (git-status)]
    (if (or (nonzero? exit)
            (re-find #"Changes (not staged for commit|to be committed)" out))
      (throw (Exception. "Uncommitted changes in project."))
      true)))

(defn clean [_]
  (b/delete {:path OUTPUT-DIR}))

(defn compile [_]
  (clean nil)
  (b/write-pom {:class-dir CLASS-DIR
                :lib       LIB
                :version   VERSION
                :basis     BASIS
                :src-dirs  ["src" "resources"]})
  (b/copy-dir {:src-dirs   ["src" "resources"]
               :target-dir CLASS-DIR})
  (b/compile-clj {:basis        BASIS
                  :src-dirs     ["src"]
                  :class-dir    CLASS-DIR}))

(defn uberjar [_]
  (assert-committed nil)
  (compile nil)
  (b/uber {:class-dir CLASS-DIR
           :uber-file UBER-FILE
           :basis     BASIS
           :main      MAIN}))

(def PWD (System/getProperty "user.dir"))

(defn success? [{:keys [exit] :as result}]
  (zero? exit))

(defn release [_]
  (uberjar nil)
  (and (success? (shell/sh (format "%s/bin/docker-login" PWD)))
       (success? (shell/sh (format "%s/bin/docker-build" PWD)))
       (success? (shell/sh (format "%s/bin/docker-push" PWD)))))

(comment
  (uberjar nil)
  (assert-committed)
  )

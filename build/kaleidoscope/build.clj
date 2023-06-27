(ns kaleidoscope.build
  (:refer-clojure :exclude [compile])
  (:require [clojure.tools.build.api :as b]
            [clojure.java.shell :as shell]
            [clojure.java.process :as process]
            [clojure.java.io :as io]))

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
  (compile nil)
  (b/uber {:class-dir CLASS-DIR
           :uber-file UBER-FILE
           :basis     BASIS
           :main      MAIN}))

(def PWD (System/getProperty "user.dir"))

(def success?
  zero?)

(defn with-real-time-output
  [process]
  (println "******** Process output **********")
  (loop [rdr (io/reader (get process :err))]
    (if-let [line (.readLine rdr)]
      (do (println line)
          (recur rdr))
      (do (println "******** End process output **********\n\n")
          @process))))

(defn log-command
  [s]
  (println (format "Executing process `%s`" s))
  s)

(def sh
  (comp with-real-time-output process/start log-command))

(defn release [_]
  ;; Add logging/verbose output
  (assert-committed nil)
  (uberjar nil)
  (and (success? (sh (format "%s/bin/docker-login" PWD)))
       (success? (sh (format "%s/bin/docker-build" PWD)))
       (success? (sh (format "%s/bin/docker-push" PWD)))))

(comment
  (uberjar nil)
  )


(comment
  (sh (format "%s/bin/docker-login" PWD))
  (sh (format "%s/bin/docker-build" PWD))
  )

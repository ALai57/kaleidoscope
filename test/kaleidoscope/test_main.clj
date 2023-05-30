(ns kaleidoscope.test-main
  "This test main allows the user to control the logging level."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.find :as ns.find]
            [taoensso.timbre :as log]))

(def ^:dynamic *test-log-level*
  :error)

(def PROJECT-DIR
  (System/getProperty "user.dir"))

(def TEST-NSES
  (ns.find/find-namespaces [(io/file (format "%s/test" PROJECT-DIR))]))

(defn -main
  [& args]
  (let [{:keys [options arguments errors summary]}
        (cli/parse-opts args
                        [["-h" "--help" "Print this help" :default false]
                         ["-L" "--level LEVEL" "Log Level"
                          :parse-fn keyword
                          :default  :warn
                          :validate [(fn [x]
                                       (contains? #{:debug
                                                    :info
                                                    :warn
                                                    :error
                                                    :fatal}
                                                  x))]]
                         ])]
    (log/infof "Running tests with %s" options)
    (log/infof "Loading test namespaces...")
    (apply require TEST-NSES)
    (binding [*test-log-level* (:level options)]
      (log/with-min-level (:level options)
        (t/run-all-tests #"kaleidoscope.*")))))

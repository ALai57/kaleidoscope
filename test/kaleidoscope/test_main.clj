(ns kaleidoscope.test-main
  "This test main allows the user to control the logging level."
  (:require [clojure.java.io :as io]
            [clojure.test :as t]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.find :as ns.find]
            [kaleidoscope.utils.logging :as ul]
            [taoensso.timbre :as log]))

(def ^:dynamic *test-log-level*
  :error)

(log/merge-config! {:appenders {:println {:output-fn ul/clean-output-fn}}})

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
                                                  x))]]])
        test-namespaces (if (empty? arguments)
                          ["kaleidoscope.*"]
                          arguments)]
    (println "****************************************************\n")
    (println (format "Testing namespaces %s" test-namespaces))
    (println (format "Running tests with %s" options))
    (println "\n****************************************************\n")
    (println "Loading test namespaces...")
    (apply require TEST-NSES)
    (println "\n****************************************************\n")
    (binding [*test-log-level* (:level options)]
      (log/with-min-level (:level options)
        (doseq [test-namespace test-namespaces]
          (t/run-all-tests (re-pattern test-namespace)))))))

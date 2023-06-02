(ns kaleidoscope.test-main
  "This test main allows the user to control the logging level."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [java-time.api :as jt]
            [clojure.test :as t]
            [clojure.tools.cli :as cli]
            [clojure.tools.namespace.find :as ns.find]
            [kaleidoscope.assertions] ;; Load custom test reporting for specs
            [taoensso.timbre :as log]
            [taoensso.encore :as enc])
  (:import (java.time ZoneId)))

(def ^:dynamic *test-log-level*
  :error)

(defn format-time
  [time-str]
  (->> (ZoneId/systemDefault)
       (jt/zoned-date-time (jt/instant time-str))
       ;; a for AM PM
       (jt/format "h:mm:ss.SS")))

(defn clean-output-fn
  [data]
  (let [{:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                timestamp_ ?line output-opts]}
        data]
    (str
     (when-let [ts (format-time (force timestamp_))] (str ts " "))
     " "
     (str/upper-case (name level))  " "
     "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] - "

     (when-let [msg-fn (get output-opts :msg-fn log/default-output-msg-fn)]
       (msg-fn data))

     (when-let [err ?err]
       (when-let [ef (get output-opts :error-fn log/default-output-error-fn)]
         (when-not   (get output-opts :no-stacktrace?) ; Back compatibility
           (str enc/system-newline
                (ef data))))))))

(log/merge-config! {:appenders {:println {:output-fn clean-output-fn}}})

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

(ns kaleidoscope.utils.logging
  (:require [clojure.string :as str]
            [java-time.api :as jt]
            [kaleidoscope.http-api.middleware :as mw]
            [steffan-westcott.clj-otel.api.trace.span :as span]
            [taoensso.encore :as enc]
            [taoensso.timbre :as log])
  (:import
   (java.time ZoneId)))

(defn format-time
  [time-str]
  (->> (ZoneId/systemDefault)
       (jt/zoned-date-time (jt/instant time-str))
       ;; a for AM PM
       (jt/format "h:mm:ss.SS")))

(defn- user-context-str
  []
  (when-let [{:keys [user-id email type]} mw/*user-context*]
    (format "[user=%s email=%s type=%s] " user-id email type)))

(defn clean-output-fn
  [data]
  (let [{:keys [level ?err #_vargs msg_ ?ns-str ?file hostname_
                timestamp_ ?line output-opts]}
        data
        span-context (span/get-span-context)]
    (str
     (when-let [ts (format-time (force timestamp_))] (str ts " "))
     " "
     (str/upper-case (name level))  " "
     "[" (or ?ns-str ?file "?") ":" (or ?line "?") "] "
     "[trace=" (.getTraceId span-context) " span=" (.getSpanId span-context) "] "
     (user-context-str)
     "- "

     (when-let [msg-fn (get output-opts :msg-fn log/default-output-msg-fn)]
       (msg-fn data))

     (when-let [err ?err]
       (when-let [ef (get output-opts :error-fn log/default-output-error-fn)]
         (when-not   (get output-opts :no-stacktrace?) ; Back compatibility
           (str enc/system-newline
                (ef data))))))))

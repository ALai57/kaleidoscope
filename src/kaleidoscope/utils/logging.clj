(ns kaleidoscope.utils.logging
  (:require [clojure.string :as str]
            [java-time.api :as jt]
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

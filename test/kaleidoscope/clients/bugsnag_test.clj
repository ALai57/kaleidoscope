(ns kaleidoscope.clients.bugsnag-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.clients.bugsnag :as bugsnag]
            [kaleidoscope.clients.error-reporter :as er])
  (:import [com.bugsnag Bugsnag Report]))

(deftest add-ex-data-tab-test
  (testing "attaches ex-data entries onto an `ex-data` metadata tab"
    (let [client (Bugsnag. "test-api-key")
          ex     (ex-info "S3 get-file error"
                          {:cognitect.anomalies/category :cognitect.anomalies/fault
                           :cognitect.anomalies/message  "Unable to fetch credentials."})
          report (.buildReport client ex)]
      (#'bugsnag/add-ex-data-tab! report ex)
      (is (= {"cognitect.anomalies/category" ":cognitect.anomalies/fault"
              "cognitect.anomalies/message"  "Unable to fetch credentials."}
             (into {} (get (.getMetaData report) "ex-data"))))))

  (testing "no-op when the exception carries no ex-data"
    (let [client (Bugsnag. "test-api-key")
          ex     (Exception. "plain error")
          report (.buildReport client ex)]
      (#'bugsnag/add-ex-data-tab! report ex)
      (is (nil? (get (.getMetaData report) "ex-data"))))))

(deftest report!-wires-ex-data-into-the-notify-callback-test
  (testing "report! attaches ex-data to the report Bugsnag actually sends"
    (let [captured (atom nil)
          client   (proxy [Bugsnag] ["test-api-key"]
                     (notify [e callback]
                       (let [report (.buildReport this e)]
                         (.beforeNotify callback report)
                         (reset! captured report)
                         true)))
          reporter (bugsnag/map->BugsnagClient {:client client})
          ex       (ex-info "S3 get-file error" {:cognitect.anomalies/category :cognitect.anomalies/fault})]
      (er/report! reporter ex)
      (is (= ":cognitect.anomalies/fault"
             (get (into {} (get (.getMetaData ^Report @captured) "ex-data")) "cognitect.anomalies/category"))))))

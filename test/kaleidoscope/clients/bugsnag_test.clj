(ns kaleidoscope.clients.bugsnag-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.clients.bugsnag :as bugsnag]
            [kaleidoscope.clients.error-reporter :as er])
  (:import [com.bugsnag Bugsnag Report]))

(deftest make-bugsnag-client-test
  (testing "wires api-key, app-version, and release-stage into the client record"
    (let [client (bugsnag/make-bugsnag-client {:api-key       "test-api-key"
                                               :app-version   "1.0"
                                               :release-stage "ephemeral-foo"})]
      (is (= "test-api-key" (:api-key client)))
      (is (= "1.0" (:app-version client)))
      (is (= "ephemeral-foo" (:release-stage client)))
      (is (instance? Bugsnag (:client client)))))

  (testing "defaults release-stage to production when unset"
    (let [client (bugsnag/make-bugsnag-client {:api-key "test-api-key"})]
      (is (nil? (:release-stage client))))))

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

(deftest set-persistence-grouping-hash-test
  (testing "groups PersistenceExceptions by table + SQLState instead of by call site"
    (let [client (Bugsnag. "test-api-key")
          ex     (ex-info "..." {:type      :PersistenceException
                                 :table     "groups"
                                 :sql-state "42883"
                                 :message   {:data {} :reason "operator does not exist"}})
          report (.buildReport client ex)]
      (#'bugsnag/set-persistence-grouping-hash! report ex)
      (is (= "PersistenceException:42883:groups" (.getGroupingHash report)))))

  (testing "two PersistenceExceptions on different tables get different hashes"
    (let [client  (Bugsnag. "test-api-key")
          ex-a    (ex-info "..." {:type :PersistenceException :table "groups" :sql-state "42883"})
          ex-b    (ex-info "..." {:type :PersistenceException :table "projects" :sql-state "42P01"})
          report-a (#'bugsnag/set-persistence-grouping-hash! (.buildReport client ex-a) ex-a)
          report-b (#'bugsnag/set-persistence-grouping-hash! (.buildReport client ex-b) ex-b)]
      (is (not= (.getGroupingHash report-a) (.getGroupingHash report-b)))))

  (testing "leaves Bugsnag's default frame-based grouping alone for non-DB exceptions"
    (let [client (Bugsnag. "test-api-key")
          ex     (ex-info "S3 get-file error" {:cognitect.anomalies/category :cognitect.anomalies/fault})
          report (.buildReport client ex)]
      (#'bugsnag/set-persistence-grouping-hash! report ex)
      (is (nil? (.getGroupingHash report))))))

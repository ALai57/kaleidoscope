(ns kaleidoscope.api.curation.anthropic-web-test
  (:require [clojure.test :refer [deftest is testing]]
            [kaleidoscope.api.curation.anthropic-web :as aw]
            [kaleidoscope.workflows.llm-executor :as llm]))

(deftest anthropic-web-fetch-extracts-document
  (testing "walks the mixed content list for the web_fetch document"
    (let [captured-body (atom nil)]
      (with-redefs [llm/post-anthropic-sync
                    (fn [_k body]
                      (reset! captured-body body)
                      {:content [{:type "web_fetch_tool_result"
                                  :content {:type "web_fetch_result"
                                            :url "https://ex/final"
                                            :content {:type "document" :source {:data "PAGE TEXT"}}}}
                                 {:type "text" :text "done"}]})]
        (let [{:keys [status raw final-url]} (aw/fetch {:url "https://ex/x"} {:api-key "k"})]
          (is (= :ok status))
          (is (= "https://ex/final" final-url))
          (is (re-find #"PAGE TEXT" (str raw))))
        (testing "request body uses snake_case :max_tokens on the wire"
          (is (contains? @captured-body :max_tokens))
          (is (not (contains? @captured-body :max-tokens))))))))

(deftest anthropic-web-fetch-returns-error-on-missing-document
  (testing "no web_fetch_tool_result block -> :error, never throws"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_k _body] {:content [{:type "text" :text "no fetch happened"}]
                                  :stop_reason "end_turn"})]
      (is (= :error (:status (aw/fetch {:url "https://ex/x"} {:api-key "k"})))))))

(deftest anthropic-web-fetch-returns-error-on-exception
  (testing "an exception from the HTTP call is caught and returned as data"
    (with-redefs [llm/post-anthropic-sync
                  (fn [_k _body] (throw (ex-info "boom" {})))]
      (is (= :error (:status (aw/fetch {:url "https://ex/x"} {:api-key "k"})))))))

(deftest anthropic-web-fetch-resends-on-pause-turn
  (testing "pause_turn re-sends messages with assistant content appended, up to 3 times"
    (let [call-count (atom 0)]
      (with-redefs [llm/post-anthropic-sync
                    (fn [_k _body]
                      (swap! call-count inc)
                      (if (< @call-count 3)
                        {:stop_reason "pause_turn" :content [{:type "text" :text "still working"}]}
                        {:content [{:type "web_fetch_tool_result"
                                    :content {:type "web_fetch_result"
                                              :url "https://ex/final"
                                              :content {:type "document" :source {:data "PAGE TEXT"}}}}]}))]
        (let [{:keys [status]} (aw/fetch {:url "https://ex/x"} {:api-key "k"})]
          (is (= :ok status))
          (is (= 3 @call-count)))))))
